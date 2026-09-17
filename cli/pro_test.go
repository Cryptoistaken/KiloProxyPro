package main

import (
	"strconv"
	"strings"
	"testing"
)

func TestRenderHevConfGolden(t *testing.T) {
	got := renderHevConf("/d", "1.2.3.4", 1080, "u", "p", false, true)
	want := "tunnel:\n" +
		"  name: tun0\n" +
		"  mtu: 1500\n" +
		"  ipv4: 10.10.10.2\n" +
		"socks5:\n" +
		"  port: 1080\n" +
		"  address: '1.2.3.4'\n" +
		"  udp: 'udp'\n" +
		"  username: 'u'\n" +
		"  password: 'p'\n" +
		"misc:\n" +
		"  log-level: info\n" +
		"  log-file: '/d/hev.log'\n" +
		"  connect-timeout: 10000\n" +
		"  udp-read-write-timeout: 60000\n"
	if got != want {
		t.Fatalf("hev.yml mismatch:\n got:\n%s\nwant:\n%s", got, want)
	}
}

func TestRenderHevConfVariants(t *testing.T) {
	noUdp := renderHevConf("/d", "1.2.3.4", 1080, "u", "p", false, false)
	if strings.Contains(noUdp, "udp:") {
		t.Fatal("associate=false must omit the udp line")
	}
	if strings.Contains(noUdp, "ipv6:") {
		t.Fatal("ipv6=false must omit the ipv6 line")
	}
	v6 := renderHevConf("/d", "1.2.3.4", 1080, "", "", true, true)
	if !strings.Contains(v6, "ipv6: 'fdfe:dcba:9876::2'") {
		t.Fatal("ipv6=true must add the tunnel ipv6 line")
	}
	if strings.Contains(v6, "username:") {
		t.Fatal("empty user must omit auth lines")
	}
	esc := renderHevConf("/d", "1.2.3.4", 1080, "o'b", "p", false, true)
	if !strings.Contains(esc, "username: 'o''b'") {
		t.Fatalf("single quotes must double, got:\n%s", esc)
	}
}

func covers(routes []string, ip string) bool {
	t, ok := ipv4ToLong(ip)
	if !ok {
		return false
	}
	for _, r := range routes {
		parts := strings.Split(r, "/")
		if len(parts) != 2 {
			continue
		}
		base, ok := ipv4ToLong(parts[0])
		if !ok {
			continue
		}
		plen, err := strconv.Atoi(strings.TrimSpace(parts[1]))
		if err != nil || plen < 0 || plen > 32 {
			continue
		}
		var mask uint32
		if plen > 0 {
			mask = ^uint32(0) << (32 - plen)
		}
		if base&mask == t&mask {
			return true
		}
	}
	return false
}

func TestExcludeIpv4DefaultRoute(t *testing.T) {
	got := excludeIpv4([]string{"0.0.0.0/0"}, "8.8.8.8")
	if len(got) != 32 {
		t.Fatalf("0.0.0.0/0 minus one host must yield 32 halves, got %d", len(got))
	}
	if covers(got, "8.8.8.8") {
		t.Fatal("excluded IP must not be covered")
	}
	for _, ip := range []string{"8.8.8.7", "8.8.8.9", "1.1.1.1", "192.168.0.1"} {
		if !covers(got, ip) {
			t.Fatalf("sibling %s must stay covered", ip)
		}
	}
}

func TestExcludeIpv4Passthrough(t *testing.T) {
	in := []string{"10.0.0.0/8", "foo", "1.2.3.4/33", "9.9.9.9/32"}
	got := excludeIpv4(in, "8.8.8.8")
	if len(got) != len(in) {
		t.Fatalf("non-covering/unparseable routes must pass through, got %v", got)
	}
	exact := excludeIpv4([]string{"8.8.8.8/32", "9.9.9.9/32"}, "8.8.8.8")
	if len(exact) != 1 || exact[0] != "9.9.9.9/32" {
		t.Fatalf("exact /32 must drop, got %v", exact)
	}
	same := excludeIpv4([]string{"0.0.0.0/0"}, "not-an-ip")
	if len(same) != 1 || same[0] != "0.0.0.0/0" {
		t.Fatalf("bad exclude IP must return routes as-is, got %v", same)
	}
}
