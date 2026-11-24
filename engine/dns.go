package engine

import (
	"strings"

	"golang.org/x/net/dns/dnsmessage"
)

// Resolver is implemented by the Android service. Forward uses the physical
// network's resolver so queries cannot be routed back into the VPN.
type Resolver interface {
	Blocked(name string) bool
	Forward(query []byte) []byte
	TunnelLost()
}

func shortAnswer(h dnsmessage.Header, q []dnsmessage.Question, code dnsmessage.RCode, truncated bool) []byte {
	reply := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID: h.ID, OpCode: h.OpCode, Response: true, RecursionAvailable: true,
			RecursionDesired: h.RecursionDesired, CheckingDisabled: h.CheckingDisabled,
			RCode: code, Truncated: truncated,
		},
		Questions: q,
	}
	wire, _ := reply.Pack()
	return wire
}

func answer(r Resolver, wire []byte, stream bool) []byte {
	var query dnsmessage.Message
	if len(wire) < 12 || query.Unpack(wire) != nil || query.Response {
		return nil
	}
	if query.OpCode != 0 {
		return shortAnswer(query.Header, nil, dnsmessage.RCodeNotImplemented, false)
	}
	if len(query.Questions) != 1 {
		return shortAnswer(query.Header, nil, dnsmessage.RCodeFormatError, false)
	}
	q := query.Questions[0]
	name := strings.TrimSuffix(strings.ToLower(q.Name.String()), ".")
	if r.Blocked(name) {
		return shortAnswer(query.Header, query.Questions, dnsmessage.RCodeNameError, false)
	}
	response := r.Forward(wire)
	var parser dnsmessage.Parser
	h, err := parser.Start(response)
	question, qerr := parser.Question()
	if err != nil || !h.Response || h.OpCode != query.OpCode || qerr != nil ||
		q.Class != question.Class || q.Type != question.Type || !strings.EqualFold(q.Name.String(), question.Name.String()) {
		return shortAnswer(query.Header, query.Questions, dnsmessage.RCodeServerFailure, false)
	}

	// Honour the client's EDNS payload size. Larger answers retry over TCP.
	if !stream {
		limit := 512
		for _, extra := range query.Additionals {
			if extra.Header.Type == dnsmessage.TypeOPT {
				limit = min(1232, max(512, int(extra.Header.Class)))
				break
			}
		}
		if len(response) > limit {
			return shortAnswer(query.Header, query.Questions, h.RCode, true)
		}
	}
	response[0], response[1] = wire[0], wire[1]
	return response
}
