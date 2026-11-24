package engine

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"os"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// A packet-preserving socket pair exercises the same file-descriptor entry
// point as VpnService. The independent client issues real UDP and TCP queries.
func TestTunnelTransports(t *testing.T) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM|unix.SOCK_NONBLOCK, 0)
	if err != nil {
		t.Fatal(err)
	}
	allowed := queryFor(t, "news.example.", dnsmessage.TypeA)
	allowed.Response = true
	allowed.Answers = []dnsmessage.Resource{{
		Header: dnsmessage.ResourceHeader{Name: allowed.Questions[0].Name, Class: dnsmessage.ClassINET, TTL: 15},
		Body:   &dnsmessage.AResource{A: [4]byte{192, 0, 2, 9}},
	}}
	server, err := Start(fds[0], "192.0.2.53", &testResolver{answer: packed(t, allowed)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	ctx, cancel := context.WithCancel(context.Background())
	client := &Session{ctx: ctx, cancel: cancel, resolve: new(testResolver), device: os.NewFile(uintptr(fds[1]), "test-client"),
		link: channel.New(32, 1500, ""), network: stack.New(stack.Options{
			NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
			TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
		})}
	defer client.Close()
	if err := client.network.CreateNIC(1, client.link); err != nil {
		t.Fatal(err)
	}
	if err := client.network.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: tcpip.AddrFrom4([4]byte{192, 0, 2, 1}).WithPrefix()}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	client.network.AddRoute(tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: 1})
	go client.readDevice()
	go client.writeDevice()
	serverAddr := tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{192, 0, 2, 53}), Port: 53}
	for _, transport := range []string{"udp", "tcp"} {
		t.Run(transport, func(t *testing.T) {
			var conn net.Conn
			var err error
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			if transport == "tcp" {
				conn, err = gonet.DialContextTCP(ctx, client.network, serverAddr, ipv4.ProtocolNumber)
			} else {
				conn, err = gonet.DialUDP(client.network, nil, &serverAddr, ipv4.ProtocolNumber)
			}
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(2 * time.Second))
			for _, tc := range []struct {
				domain  string
				qtype   dnsmessage.Type
				rcode   dnsmessage.RCode
				answers int
			}{
				{"ads.example.", dnsmessage.TypeA, dnsmessage.RCodeNameError, 0},
				{"ads.example.", dnsmessage.TypeAAAA, dnsmessage.RCodeNameError, 0},
				{"news.example.", dnsmessage.TypeA, dnsmessage.RCodeSuccess, 1},
			} {
				wire := packed(t, queryFor(t, tc.domain, tc.qtype))
				reply := make([]byte, 1500)
				if transport == "tcp" {
					wire = append([]byte{byte(len(wire) >> 8), byte(len(wire))}, wire...)
				}
				if _, err := conn.Write(wire); err != nil {
					t.Fatal(err)
				}
				if transport == "tcp" {
					if _, err := io.ReadFull(conn, reply[:2]); err != nil {
						t.Fatal(err)
					}
					reply = reply[:binary.BigEndian.Uint16(reply[:2])]
					if _, err := io.ReadFull(conn, reply); err != nil {
						t.Fatal(err)
					}
				} else {
					n, err := conn.Read(reply)
					if err != nil {
						t.Fatal(err)
					}
					reply = reply[:n]
				}
				response := decoded(t, reply)
				if response.RCode != tc.rcode || response.Questions[0].Type != tc.qtype || len(response.Answers) != tc.answers {
					t.Fatal(response)
				}
			}
		})
	}
}
