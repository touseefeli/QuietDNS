// Package engine serves DNS over UDP and TCP on one address inside an Android
// VPN interface. Only traffic to the virtual DNS server is routed here.
package engine

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"time"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

type Session struct {
	device   *os.File
	link     *channel.Endpoint
	network  *stack.Stack
	datagram *gonet.UDPConn
	listener *gonet.TCPListener
	resolve  Resolver
	ctx      context.Context
	cancel   context.CancelFunc
	once     sync.Once
	lost     sync.Once
}

// Start takes ownership of the file descriptor, including on startup errors.
func Start(fd int, serverIP string, resolver Resolver) (*Session, error) {
	if err := unix.SetNonblock(fd, true); err != nil {
		unix.Close(fd)
		return nil, err
	}
	device := os.NewFile(uintptr(fd), "dns-tunnel")
	ip := net.ParseIP(serverIP).To4()
	if ip == nil {
		device.Close()
		return nil, errors.New("invalid virtual DNS address")
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Session{
		device: device, link: channel.New(128, 1500, ""), resolve: resolver,
		network: stack.New(stack.Options{
			NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
			TransportProtocols: []stack.TransportProtocolFactory{udp.NewProtocol, tcp.NewProtocol},
		}),
		ctx: ctx, cancel: cancel,
	}
	addr := tcpip.AddrFromSlice(ip)
	if err := s.network.CreateNIC(1, s.link); err != nil {
		s.Close()
		return nil, fmt.Errorf("DNS interface: %v", err)
	}
	if err := s.network.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol: ipv4.ProtocolNumber, AddressWithPrefix: addr.WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		s.Close()
		return nil, fmt.Errorf("DNS address: %v", err)
	}
	s.network.AddRoute(tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: 1})
	endpoint := tcpip.FullAddress{NIC: 1, Addr: addr, Port: 53}
	var err error
	if s.datagram, err = gonet.DialUDP(s.network, &endpoint, nil, ipv4.ProtocolNumber); err != nil {
		s.Close()
		return nil, err
	}
	if s.listener, err = gonet.ListenTCP(s.network, endpoint, ipv4.ProtocolNumber); err != nil {
		s.Close()
		return nil, err
	}
	go s.readDevice()
	go s.writeDevice()
	go s.serveUDP()
	go s.serveTCP()
	return s, nil
}

func (s *Session) failure() {
	if s.ctx.Err() == nil {
		s.lost.Do(s.resolve.TunnelLost)
	}
}

func (s *Session) readDevice() {
	buf := make([]byte, 65535)
	for {
		n, err := s.device.Read(buf)
		if err != nil {
			s.failure()
			return
		}
		if n == 0 || buf[0]>>4 != 4 {
			continue
		}
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), buf[:n]...))})
		s.link.InjectInbound(ipv4.ProtocolNumber, pkt)
		pkt.DecRef()
	}
}

func (s *Session) writeDevice() {
	for {
		pkt := s.link.ReadContext(s.ctx)
		if pkt == nil {
			return
		}
		view := pkt.ToView()
		pkt.DecRef()
		_, err := s.device.Write(view.AsSlice())
		view.Release()
		if err != nil {
			s.failure()
			return
		}
	}
}

func (s *Session) serveUDP() {
	slots := make(chan struct{}, 64)
	buf := make([]byte, 65535)
	for {
		n, addr, err := s.datagram.ReadFrom(buf)
		if err != nil {
			s.failure()
			return
		}
		select {
		case slots <- struct{}{}:
			wire := append([]byte(nil), buf[:n]...)
			go func() {
				defer func() { <-slots }()
				if reply := answer(s.resolve, wire, false); reply != nil && s.ctx.Err() == nil {
					s.datagram.WriteTo(reply, addr)
				}
			}()
		case <-s.ctx.Done():
			return
		}
	}
}

func (s *Session) serveTCP() {
	slots := make(chan struct{}, 32)
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			s.failure()
			return
		}
		select {
		case slots <- struct{}{}:
			go func() {
				defer func() { conn.Close(); <-slots }()
				s.tcpQueries(conn)
			}()
		default:
			conn.Close()
		}
	}
}

func (s *Session) tcpQueries(conn net.Conn) {
	var size [2]byte
	for s.ctx.Err() == nil {
		conn.SetDeadline(time.Now().Add(20 * time.Second))
		if _, err := io.ReadFull(conn, size[:]); err != nil {
			return
		}
		wire := make([]byte, binary.BigEndian.Uint16(size[:]))
		if _, err := io.ReadFull(conn, wire); err != nil {
			return
		}
		reply := answer(s.resolve, wire, true)
		if len(reply) == 0 || len(reply) > 65535 {
			return
		}
		binary.BigEndian.PutUint16(size[:], uint16(len(reply)))
		if _, err := conn.Write(append(size[:], reply...)); err != nil {
			return
		}
	}
}

func (s *Session) Close() {
	s.once.Do(func() {
		s.cancel()
		s.device.Close()
		if s.datagram != nil {
			s.datagram.Close()
		}
		if s.listener != nil {
			s.listener.Close()
		}
		s.network.Close()
		s.link.Close()
		s.network.Wait()
	})
}
