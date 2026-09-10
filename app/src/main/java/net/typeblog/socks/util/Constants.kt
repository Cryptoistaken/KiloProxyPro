package net.typeblog.socks.util

object Constants {
    const val ROUTE_ALL = "all"
    const val ROUTE_CHN = "chn"
    const val ROUTE_RU = "ru"
    const val ROUTE_RU_CHN = "ru_chn"
    const val INTENT_PREFIX = "SOCKS"
    const val INTENT_NAME = INTENT_PREFIX + "NAME"
    const val INTENT_SERVER = INTENT_PREFIX + "SERV"
    const val INTENT_PORT = INTENT_PREFIX + "PORT"
    const val INTENT_USERNAME = INTENT_PREFIX + "UNAME"
    const val INTENT_PASSWORD = INTENT_PREFIX + "PASSWD"
    const val INTENT_ROUTE = INTENT_PREFIX + "ROUTE"
    const val INTENT_DNS = INTENT_PREFIX + "DNS"
    const val INTENT_DNS_PORT = INTENT_PREFIX + "DNSPORT"
    const val INTENT_PER_APP = INTENT_PREFIX + "PERAPP"
    const val INTENT_APP_BYPASS = INTENT_PREFIX + "APPBYPASS"
    const val INTENT_APP_LIST = INTENT_PREFIX + "APPLIST"
    const val INTENT_IPV6_PROXY = INTENT_PREFIX + "IPV6"
    const val INTENT_UDP_GW = INTENT_PREFIX + "UDPGW"
    const val PREF = "profile"
    const val PREF_PROFILE = "profile"
    const val PREF_LAST_PROFILE = "last_profile"
    const val PREF_ADV_PER_APP = "adv_per_app"
    const val PREF_ADV_APP_BYPASS = "adv_app_bypass"
    const val PREF_ADV_APP_LIST = "adv_app_list"
    const val PREF_THEME_MODE = "theme_mode"
    const val PREF_AUTO_STOP = "auto_stop"
    // Accelerator master gate for repeat-connect experiments. Default OFF,
    // which keeps every path below on stock behavior.
    const val PREF_VPN_ACCELERATOR = "vpn_accelerator"
    // Advanced Settings options. Honored by the engine only while the
    // accelerator master toggle is ON. Defaults preserve stock behavior.
    const val PREF_ACCEL_PRIMARY = "accel_primary"
    const val ACCEL_PRIMARY_TRACE = "trace"
    const val ACCEL_PRIMARY_KILOIP = "kiloip"
    const val PREF_ACCEL_MODE = "accel_mode"
    const val ACCEL_MODE_BOTH = "both"
    const val ACCEL_MODE_SINGLE = "single"
    const val PREF_ACCEL_CACHE_IP = "accel_cache_ip"
    const val PREF_ACCEL_PROBE = "accel_probe"
    const val PREF_ACCEL_INTERVAL_MS = "accel_interval_ms"
    const val PREF_ACCEL_DNS_CACHE = "accel_dns_cache"
    // Experimental engine swap: hev-socks5-tunnel instead of badvpn
    // tun2socks + pdnsd. Default OFF = stock behavior. Independent of
    // the accelerator master toggle.
    const val PREF_HEV_TUNNEL = "hev_tunnel"

    const val PREF_FLOATING_CONTROL = "floating_control"
    const val PREF_BUBBLE_STYLE = "bubble_style"
    const val BUBBLE_STYLE_LOCK = "lock"
    const val BUBBLE_STYLE_CLASSIC = "classic"
    const val PREF_BUBBLE_X = "bubble_x"
    const val PREF_BUBBLE_Y = "bubble_y"
    const val PREF_SKIPPED_UPDATE_VERSION = "skipped_update_version"
    // Split-tunnel: global keys (PREF_ADV_*) are single source of truth (written by SplitTunnelingScreen);
    // Profile keys "perapp"/"appbypass"/"applist" are legacy per-profile aliases kept for compat.
    // Unique to this app's package so a sibling app built from the same base
    // code can never wake our receivers with its own broadcasts (and vice versa).
    const val ACTION_STOP_VPN = "com.kiloproxy.pro.STOP_VPN"
    const val ACTION_START_VPN = "com.kiloproxy.pro.START_VPN"
    const val ACTION_VPN_STATE_CHANGED = "com.kiloproxy.pro.VPN_STATE_CHANGED"
    const val VPN_STATE_RUNNING = "running"
    const val VPN_STATE_TUNNEL_UP = "tunnel_up"
    const val VPN_STATE_VERIFIED = "verified"
    const val VPN_STATE_CONNECTED = "connected"
    const val VPN_STATE_CONNECTED_SINCE = "connected_since"
    const val VPN_STATE_IP = "ip"
    const val VPN_STATE_COUNTRY_CODE = "country_code"
    const val VPN_STATE_COUNTRY = "country"
    const val VPN_STATE_REGION = "region"
    const val VPN_STATE_CITY = "city"
    const val VPN_STATE_ISP = "isp"
    const val VPN_STATE_ORG = "org"
    const val VPN_STATE_AS_NAME = "as_name"
    const val VPN_STATE_TIMEZONE = "timezone"
    const val VPN_STATE_ERROR = "error"
    const val VPN_STATE_RECEIVED = "received"
    const val VPN_STATE_SENT = "sent"
    const val VPN_STATE_PROFILE = "profile"
}
