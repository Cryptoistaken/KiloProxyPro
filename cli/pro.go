// Pro-only commands: hevconf (makeHevConf parity), routes
// (Routes.excludeIpv4 parity), udp (hev UDP-ASSOCIATE path probe).
package main

import (
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// ---- hev.yml rendering (Utility.makeHevConf parity) ----

// renderHevConf builds the exact YAML the app writes for hev-socks5-tunnel:
// tunnel mtu/ipv4 (+ipv6), socks5 addr/port/auth (+ udp line when
// associate), misc log/timeouts. yq escapes single quotes like the app.
func renderHevConf(logDir, serverIP string, port int, user, passwd string, ipv6, associate bool) string {
	var sb strings.Builder
	sb.WriteString("tunnel:\n")
	sb.WriteString("  name: tun0\n")
	sb.WriteString("  mtu: 1500\n")
	sb.WriteString("  ipv4: 10.10.10.2\n")
	if ipv6 {
		sb.WriteString("  ipv6: 'fdfe:dcba:9876::2'\n")
	}
	sb.WriteString("socks5:\n")
	fmt.Fprintf(&sb, "  port: %d\n", port)
	fmt.Fprintf(&sb, "  address: '%s'\n", yq(serverIP))
	if associate {
		sb.WriteString("  udp: 'udp'\n")
	}
	if user != "" {
		fmt.Fprintf(&sb, "  username: '%s'\n", yq(user))
		fmt.Fprintf(&sb, "  password: '%s'\n", yq(passwd))
	}
	sb.WriteString("misc:\n")
	sb.WriteString("  log-level: info\n")
	fmt.Fprintf(&sb, "  log-file: '%s/hev.log'\n", logDir)
	sb.WriteString("  connect-timeout: 10000\n")
	sb.WriteString("  udp-read-write-timeout: 60000\n")
	return sb.String()
}

// yq mirrors Utility.yq: YAML single-quote escape.
func yq(s string) string { return strings.ReplaceAll(s, "'", "''") }

func runHevConf(args []string) int {
	var c proxyCfg
	var logDir string
	var ipv6, associate bool
	fs := flag.NewFlagSet("hevconf", flag.ContinueOnError)
	addProxyFlags(fs, &c)
	fs.StringVar(&logDir, "logdir", "/data/data/com.kiloproxy.pro/files", "files dir owning hev.log (app filesDir)")
	fs.BoolVar(&ipv6, "ipv6", false, "include tunnel ipv6 address")
	fs.BoolVar(&associate, "udp", false, "render the udp associate line (app never emits it; proxies are TCP-only)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if c.host == "" {
		fmt.Fprintln(os.Stderr, "hevconf needs -host (render against the resolved server IP like the app does)")
		return 2
	}
	fmt.Print(renderHevConf(logDir, c.host, c.port, c.user, c.pass, ipv6, associate))
	return 0
}

// ---- route carve-out (Routes.excludeIpv4 parity) ----

func ipv4ToLong(ip string) (uint32, bool) {
	parts := strings.Split(strings.TrimSpace(ip), ".")
	if len(parts) != 4 {
		return 0, false
	}
	var v uint32
	for _, p := range parts {
		o, err := strconv.Atoi(p)
		if err != nil || o < 0 || o > 255 {
			return 0, false
		}
		v = v<<8 | uint32(o)
	}
	return v, true
}

func longToIpv4(v uint32) string {
	return fmt.Sprintf("%d.%d.%d.%d", v>>24&0xff, v>>16&0xff, v>>8&0xff, v&0xff)
}

// excludeIpv4 mirrors Routes.excludeIpv4: cover the same space minus one
// host /32, splitting covering supernets into sibling halves.
func excludeIpv4(routes []string, ip string) []string {
	target, ok := ipv4ToLong(ip)
	if !ok {
		return routes
	}
	out := make([]string, 0, len(routes))
	for _, r := range routes {
		parts := strings.Split(r, "/")
		if len(parts) != 2 {
			out = append(out, r)
			continue
		}
		base, ok := ipv4ToLong(parts[0])
		if !ok {
			out = append(out, r)
			continue
		}
		plen, err := strconv.Atoi(strings.TrimSpace(parts[1]))
		if err != nil || plen < 0 || plen > 32 {
			out = append(out, r)
			continue
		}
		if plen == 32 {
			if base != target {
				out = append(out, r)
			}
			continue
		}
		var mask uint32
		if plen > 0 {
			mask = ^uint32(0) << (32 - plen)
		}
		if base&mask != target&mask {
			out = append(out, r)
			continue
		}
		curBase, curLen := base, plen
		for curLen < 32 {
			half := uint32(1) << (32 - curLen - 1)
			mid := curBase + half
			if target < mid {
				out = append(out, fmt.Sprintf("%s/%d", longToIpv4(mid), curLen+1))
				curLen++
			} else {
				out = append(out, fmt.Sprintf("%s/%d", longToIpv4(curBase), curLen+1))
				curBase, curLen = mid, curLen+1
			}
		}
	}
	return out
}

func runRoutes(args []string) int {
	var exclude, list string
	fs := flag.NewFlagSet("routes", flag.ContinueOnError)
	fs.StringVar(&exclude, "exclude", "", "host IP to carve out (hev Builder DNS server)")
	fs.StringVar(&list, "routes", "0.0.0.0/0", "comma-separated route list")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if exclude == "" {
		fmt.Fprintln(os.Stderr, "routes needs -exclude (the DNS IP carved out of the tunnel)")
		return 2
	}
	routes := strings.Split(list, ",")
	for _, r := range excludeIpv4(routes, exclude) {
		fmt.Println(strings.TrimSpace(r))
	}
	return 0
}

// ---- udp ----

func runUDP(args []string) int {
	var c proxyCfg
	var qname, resolver string
	var timeout, udpWait time.Duration
	fs := flag.NewFlagSet("udp", flag.ContinueOnError)
	addProxyFlags(fs, &c)
	fs.StringVar(&qname, "name", "google.com", "DNS A query sent through the relay")
	fs.StringVar(&resolver, "resolver", "8.8.8.8", "resolver IP (profile DNS, fallback 8.8.8.8)")
	fs.DurationVar(&timeout, "timeout", 5*time.Second, "TCP phase timeout")
	fs.DurationVar(&udpWait, "udp-wait", 5*time.Second, "datagram round-trip wait (hev uses 60s)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if c.host == "" {
		fmt.Fprintln(os.Stderr, "udp needs -host")
		return 2
	}
	res := UdpAssociateTest(c.host, c.port, c.user, c.pass, qname, resolverIP(resolver), timeout, udpWait)
	fmt.Printf("proxy:  %s:%d\n", c.host, c.port)
	fmt.Printf("query:  %s via %s\n", qname, resolver)
	fmt.Printf("result: %s (%s) %sms\n", udpDisplay(res.Outcome), res.Outcome, ms(res.Elapsed))
	if len(res.Answers()) > 0 {
		fmt.Printf("answers: %s\n", strings.Join(res.Answers(), " "))
	}
	if res.Detail != "" {
		fmt.Printf("detail: %s\n", res.Detail)
	}
	if res.Outcome != UdpOK {
		return 1
	}
	return 0
}

func resolverIP(r string) string {
	if h, _, err := splitHostPortSafe(r); err == nil {
		return h
	}
	return r
}

func splitHostPortSafe(s string) (string, string, error) {
	if !strings.Contains(s, ":") {
		return s, "", fmt.Errorf("no port")
	}
	i := strings.LastIndex(s, ":")
	return s[:i], s[i+1:], nil
}
