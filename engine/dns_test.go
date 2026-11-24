package engine

import (
	"bytes"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

type testResolver struct {
	calls  int
	answer []byte
}

func (r *testResolver) Blocked(name string) bool { return name == "ads.example" }
func (r *testResolver) Forward([]byte) []byte    { r.calls++; return append([]byte(nil), r.answer...) }
func (r *testResolver) TunnelLost()              {}

func queryFor(t *testing.T, domain string, kind dnsmessage.Type) dnsmessage.Message {
	t.Helper()
	return dnsmessage.Message{Header: dnsmessage.Header{ID: 43, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName(domain), Type: kind, Class: dnsmessage.ClassINET}}}
}
func packed(t *testing.T, m dnsmessage.Message) []byte {
	t.Helper()
	b, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func decoded(t *testing.T, b []byte) dnsmessage.Message {
	t.Helper()
	var m dnsmessage.Message
	if err := m.Unpack(b); err != nil {
		t.Fatal(err)
	}
	return m
}
func TestBlockedLocallyAcrossTypes(t *testing.T) {
	r := new(testResolver)
	for _, typ := range []dnsmessage.Type{dnsmessage.TypeA, dnsmessage.TypeAAAA, 65} {
		q := queryFor(t, "Ads.Example.", typ)
		for _, stream := range []bool{true, false} {
			reply := decoded(t, answer(r, packed(t, q), stream))
			if reply.ID != q.ID || !reply.Response || reply.RCode != dnsmessage.RCodeNameError || reply.Questions[0] != q.Questions[0] {
				t.Fatalf("incorrect blocked reply: %+v", reply)
			}
		}
	}
	if r.calls != 0 {
		t.Fatal("blocked names reached the upstream")
	}
}

func TestAllowedAndInvalidUpstream(t *testing.T) {
	q := queryFor(t, "example.org.", dnsmessage.TypeA)
	wire := packed(t, q)
	r := new(testResolver)
	response := q
	response.Response = true
	response.ID = 999
	response.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: q.Questions[0].Name, Class: dnsmessage.ClassINET, TTL: 30}, Body: &dnsmessage.AResource{A: [4]byte{192, 0, 2, 9}}}}
	r.answer = packed(t, response)
	got := decoded(t, answer(r, wire, false))
	if got.ID != q.ID || len(got.Answers) != 1 || got.RCode != 0 {
		t.Fatal(got)
	}
	response.Questions = queryFor(t, "other.example.", dnsmessage.TypeA).Questions
	for _, invalid := range [][]byte{nil, {0, 1}, packed(t, response)} {
		r.answer = invalid
		if m := decoded(t, answer(r, wire, false)); m.RCode != dnsmessage.RCodeServerFailure {
			t.Fatal(m)
		}
	}
}
func TestLargeResponseRetriesOverTCP(t *testing.T) {
	q := queryFor(t, "example.org.", dnsmessage.TypeTXT)
	up := q
	up.Response = true
	up.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: q.Questions[0].Name, Class: dnsmessage.ClassINET}, Body: &dnsmessage.TXTResource{TXT: []string{string(bytes.Repeat([]byte{'x'}, 250)), string(bytes.Repeat([]byte{'y'}, 250))}}}}
	r := &testResolver{answer: packed(t, up)}
	if m := decoded(t, answer(r, packed(t, q), false)); !m.Truncated || len(m.Answers) != 0 {
		t.Fatal(m)
	}
	if m := decoded(t, answer(r, packed(t, q), true)); m.Truncated || len(m.Answers) != 1 {
		t.Fatal(m)
	}
	q.Additionals = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName("."), Class: 1232}, Body: &dnsmessage.OPTResource{}}}
	if m := decoded(t, answer(r, packed(t, q), false)); m.Truncated || len(m.Answers) != 1 {
		t.Fatal(m)
	}
}
