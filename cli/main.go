// Command kiloproxy-pro is the on-device (Termux) test harness for the
// portable half of the KiloProxy Pro engine: everything the stock CLI
// covers (SocksTester.kt handshake, Utility.checkWith exit-IP path) plus
// the Pro-only hev Fast-tunnel surface: UDP ASSOCIATE relay probing
// (PREF_HEV_UDP), hev.yml rendering (makeHevConf), and the route
// carve-out that keeps profile DNS outside the tunnel
// (Routes.excludeIpv4). Zero dependencies, stdlib only.
//
// Like the stock CLI it cannot drive the Android-only half (VpnService
// TUN, native hev/tun2socks/pdnsd): tunnel throughput and packet
// behavior still need the app.
package main

import (
	"flag"
	"fmt"
	"os"
	"sort"
	"strings"
	"time"
)

type proxyCfg struct {
	host string
	port int
	user string
	pass string
}

func addProxyFlags(fs *flag.FlagSet, c *proxyCfg) {
	fs.StringVar(&c.host, "host", "", "SOCKS5 proxy host (empty = direct, no proxy)")
	fs.IntVar(&c.port, "port", 1080, "SOCKS5 proxy port")
	fs.StringVar(&c.user, "user", "", "proxy username")
	fs.StringVar(&c.pass, "pass", "", "proxy password")
}

// parseSweepLine parses "host:port[:user:pass]" sweep-file lines.
func parseSweepLine(line string) (proxyCfg, error) {
	var c proxyCfg
	line = strings.TrimSpace(line)
	if line == "" || strings.HasPrefix(line, "#") {
		return c, fmt.Errorf("skip")
	}
	var parts []string
	if strings.HasPrefix(line, "[") {
		h, rest, err := splitBracket(line)
		if err != nil {
			return c, err
		}
		parts = append([]string{h}, strings.Split(rest, ":")...)
	} else {
		parts = strings.Split(line, ":")
	}
	if len(parts) < 2 || len(parts) > 4 {
		return c, fmt.Errorf("want host:port[:user:pass], got %q", line)
	}
	c.host = parts[0]
	if _, err := fmt.Sscanf(parts[1], "%d", &c.port); err != nil || c.port <= 0 || c.port > 65535 {
		return c, fmt.Errorf("bad port in %q", line)
	}
	if len(parts) > 2 {
		c.user = parts[2]
	}
	if len(parts) > 3 {
		c.pass = parts[3]
	}
	return c, nil
}

func splitBracket(line string) (host, rest string, err error) {
	end := strings.Index(line, "]")
	if end < 0 {
		return "", "", fmt.Errorf("bad IPv6 literal in %q", line)
	}
	host = line[1:end]
	rest = strings.TrimPrefix(line[end+1:], ":")
	return host, rest, nil
}

// durStats summarizes durations as min/median/avg/max.
type durStats struct {
	n        int
	min, med time.Duration
	avg      time.Duration
	max      time.Duration
}

func summarize(ds []time.Duration) durStats {
	var s durStats
	if len(ds) == 0 {
		return s
	}
	cp := append([]time.Duration(nil), ds...)
	sort.Slice(cp, func(i, j int) bool { return cp[i] < cp[j] })
	s.n = len(cp)
	s.min = cp[0]
	s.max = cp[len(cp)-1]
	s.med = cp[len(cp)/2]
	var sum time.Duration
	for _, d := range cp {
		sum += d
	}
	s.avg = sum / time.Duration(len(cp))
	return s
}

func ms(d time.Duration) string { return fmt.Sprintf("%d", d.Milliseconds()) }

func usage() {
	fmt.Fprintf(os.Stderr, `kiloproxy-pro - on-device test harness for the KiloProxy Pro SOCKS5 engine half

Usage: kiloproxy-pro <command> [flags]

Commands:
  probe   SOCKS5 handshake probe (SocksTester parity)
  check   exit-IP + geo lookup through the proxy (Utility.checkWith parity)
  bench   repeat probe+check N times with timing stats (connect-time analysis)
  sweep   probe+check every proxy in a file (host:port[:user:pass] per line)
  speed   bulk download through the proxy, reports throughput
  dns     resolve a hostname like the engine (IPv4 preferred) with timing
  udp     UDP ASSOCIATE relay test with DNS query (hev udp mode, Pro-only)
  hevconf render the hev.yml the app would feed hev-socks5-tunnel (Pro-only)
  routes  carve one host out of a route list (hev DNS carve-out, Pro-only)

probe/check/bench/sweep/speed/udp take -host -port -user -pass.
Empty -host means direct (no proxy): the baseline the app compares against.
Run 'kiloproxy-pro <command> -h' for command flags.
`)
}

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var code int
	switch os.Args[1] {
	case "probe":
		code = runProbe(os.Args[2:])
	case "check":
		code = runCheck(os.Args[2:])
	case "bench":
		code = runBench(os.Args[2:])
	case "sweep":
		code = runSweep(os.Args[2:])
	case "speed":
		code = runSpeed(os.Args[2:])
	case "dns":
		code = runDNS(os.Args[2:])
	case "udp":
		code = runUDP(os.Args[2:])
	case "hevconf":
		code = runHevConf(os.Args[2:])
	case "routes":
		code = runRoutes(os.Args[2:])
	case "-h", "-help", "--help", "help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", os.Args[1])
		usage()
		code = 2
	}
	os.Exit(code)
}
