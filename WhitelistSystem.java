import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class WhitelistSystem {

    static final String DATA_DIR = "wl_data";
    static final String CONFIG_FILE = DATA_DIR + "/config.json";
    static final String USERS_FILE = DATA_DIR + "/users.json";
    static final String QUEST_FILE = DATA_DIR + "/questionnaire.json";
    static final String APP_FILE = DATA_DIR + "/applications.json";
    static final String IMG_DIR = DATA_DIR + "/images";
    static final String IMG_META_FILE = DATA_DIR + "/images_meta.json";
    static final String APPROVED_QQ_FILE = DATA_DIR + "/approved_qq.json";

    static Map<String, Object> config;
    static List<Map<String, Object>> users;
    static Map<String, Object> questionnaire;
    static List<Map<String, Object>> applications;
    static List<Map<String, Object>> imagesMeta;
    static Set<String> approvedQQs = ConcurrentHashMap.newKeySet();

    static final Map<String, String> userSessions = new ConcurrentHashMap<>();
    static final Map<String, String> adminSessions = new ConcurrentHashMap<>();
    static final Map<String, Long> sessionExpiry = new ConcurrentHashMap<>();
    static final long SESSION_TIMEOUT = 86400000L;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        init();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            ExecutorService exec = Executors.newFixedThreadPool(4);
            server.setExecutor(exec);
            server.createContext("/", t -> { redirect(t, "/user/login"); });
            server.createContext("/user/login", t -> servePage(t, userLoginPage()));
            server.createContext("/user/dashboard", t -> servePage(t, userDashboardPage(t)));
            server.createContext("/user/apply", t -> servePage(t, userApplyPage(t)));
            server.createContext("/user/status", t -> servePage(t, userStatusPage(t)));
            server.createContext("/admin/login", t -> servePage(t, adminLoginPage()));
            server.createContext("/admin", t -> { redirect(t, "/admin/dashboard"); });
            server.createContext("/admin/dashboard", t -> servePage(t, adminDashboardPage(t)));
            server.createContext("/admin/settings", t -> servePage(t, adminSettingsPage(t)));
            server.createContext("/admin/questionnaire", t -> servePage(t, adminQuestPage(t)));
            server.createContext("/admin/images", t -> servePage(t, adminImagesPage(t)));
            server.createContext("/admin/review", t -> servePage(t, adminReviewPage(t)));
            server.createContext("/images", t -> serveImage(t));
            server.createContext("/api/user/login", t -> apiUserLogin(t));
            server.createContext("/api/user/logout", t -> apiLogout(t, false));
            server.createContext("/api/user/apply", t -> apiUserApply(t));
            server.createContext("/api/user/application", t -> apiUserApplication(t));
            server.createContext("/api/admin/login", t -> apiAdminLogin(t));
            server.createContext("/api/admin/logout", t -> apiLogout(t, true));
            server.createContext("/api/admin/config", t -> apiAdminConfig(t));
            server.createContext("/api/admin/questionnaire", t -> apiAdminQuestionnaire(t));
            server.createContext("/api/admin/applications", t -> apiAdminApplications(t));
            server.createContext("/api/admin/review", t -> apiAdminReview(t));
            server.createContext("/api/admin/images", t -> apiAdminImages(t));
            server.createContext("/api/napcat/webhook", t -> apiNapcatWebhook(t));
            server.start();
            System.out.println("========================================");
            System.out.println("  Minecraft 白名单管理系统已启动");
            System.out.println("  访问地址: http://localhost:" + port);
            System.out.println("  用户端:   http://localhost:" + port + "/user/login");
            System.out.println("  管理端:   http://localhost:" + port + "/admin/login");
            System.out.println("  NapCat回调: http://localhost:" + port + "/api/napcat/webhook");
            System.out.println("========================================");
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static class JsonParser {
        private final String s; private int i = 0;
        JsonParser(String s) { this.s = s.trim(); }
        Object parse() { skipWS(); Object r = parseValue(); skipWS(); return r; }
        private void skipWS() { while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) i++; }
        private char peek() { return i < s.length() ? s.charAt(i) : 0; }
        private Object parseValue() {
            char c = peek();
            if (c == '{') return parseObj(); if (c == '[') return parseArr();
            if (c == '"') return parseStr(); if (c == 't') { i += 4; return true; }
            if (c == 'f') { i += 5; return false; } if (c == 'n') { i += 4; return null; }
            return parseNum();
        }
        private Map<String, Object> parseObj() {
            i++; Map<String, Object> m = new LinkedHashMap<>(); skipWS();
            if (peek() == '}') { i++; return m; }
            while (true) { skipWS(); String key = parseStr(); skipWS(); i++; skipWS();
                Object val = parseValue(); m.put(key, val); skipWS();
                if (peek() == '}') { i++; break; } i++; } return m;
        }
        private List<Object> parseArr() {
            i++; List<Object> a = new ArrayList<>(); skipWS();
            if (peek() == ']') { i++; return a; }
            while (true) { skipWS(); a.add(parseValue()); skipWS();
                if (peek() == ']') { i++; break; } i++; } return a;
        }
        private String parseStr() {
            i++; StringBuilder sb = new StringBuilder();
            while (i < s.length()) { char c = s.charAt(i);
                if (c == '\\') { i++; char e = s.charAt(i);
                    if (e == '"') sb.append('"'); else if (e == '\\') sb.append('\\');
                    else if (e == 'n') sb.append('\n'); else if (e == 'r') sb.append('\r');
                    else if (e == 't') sb.append('\t'); else sb.append(e);
                } else if (c == '"') { i++; break; } else sb.append(c); i++; }
            return sb.toString();
        }
        private Number parseNum() {
            int start = i; if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean isFloat = false;
            if (i < s.length() && s.charAt(i) == '.') { isFloat = true; i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
            String num = s.substring(start, i);
            return isFloat ? Double.parseDouble(num) : Long.parseLong(num);
        }
    }

    @SuppressWarnings("unchecked")
    static Object parseJson(String s) { return new JsonParser(s).parse(); }

    static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) obj;
            StringBuilder sb = new StringBuilder("{"); boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(esc(e.getKey())).append("\":").append(toJson(e.getValue()));
                first = false; } return sb.append("}").toString();
        }
        if (obj instanceof List) {
            List<?> l = (List<?>) obj; StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(","); sb.append(toJson(l.get(i))); }
            return sb.append("]").toString();
        }
        if (obj instanceof String) return "\"" + esc((String) obj) + "\"";
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
        return "\"" + esc(obj.toString()) + "\"";
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static String str(Map<String, Object> m, String k) { return m.containsKey(k) && m.get(k) != null ? m.get(k).toString() : ""; }
    static long lng(Map<String, Object> m, String k) { Object v = m.get(k); if (v instanceof Number) return ((Number) v).longValue(); try { return Long.parseLong(str(m, k)); } catch (Exception e) { return 0; } }
    static boolean bool(Map<String, Object> m, String k) { Object v = m.get(k); if (v instanceof Boolean) return (Boolean) v; return "true".equalsIgnoreCase(str(m, k)); }

    @SuppressWarnings("unchecked")
    static synchronized void init() {
        new File(DATA_DIR).mkdirs(); new File(IMG_DIR).mkdirs();
        config = loadMap(CONFIG_FILE, defaultConfig());
        users = loadList(USERS_FILE);
        questionnaire = loadMap(QUEST_FILE, defaultQuestionnaire());
        applications = loadList(APP_FILE);
        imagesMeta = loadList(IMG_META_FILE);
        List<Map<String, Object>> aqq = loadList(APPROVED_QQ_FILE);
        approvedQQs.clear();
        for (Object o : aqq) if (o instanceof String) approvedQQs.add((String) o);
        saveAll();
    }

    static Map<String, Object> defaultConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adminUsername", "admin"); m.put("adminPassword", sha256("admin123"));
        m.put("rconHost", "127.0.0.1"); m.put("rconPort", 25575L); m.put("rconPassword", "");
        m.put("napcatHost", "127.0.0.1"); m.put("napcatPort", 3000L); m.put("qqGroup", "");
        m.put("passScore", 60L); m.put("serverName", "我的世界服务器");
        m.put("serverDescription", "欢迎来到我们的服务器！");
        return m;
    }

    static Map<String, Object> defaultQuestionnaire() {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("title", "白名单申请问卷");
        List<Map<String, Object>> qs = new ArrayList<>();
        qs.add(mkQ(1, "choice", "你的年龄范围是？", Arrays.asList("18岁以下", "18-25岁", "26-35岁", "35岁以上"), 10));
        qs.add(mkQ(2, "choice", "你的Minecraft游玩经验？", Arrays.asList("新手", "有一定经验", "老玩家", "硬核玩家"), 10));
        qs.add(mkQ(3, "text", "你为什么想加入本服务器？", null, 30));
        qs.add(mkQ(4, "text", "你在服务器中打算做什么？", null, 30));
        qs.add(mkQ(5, "choice", "你是否同意遵守服务器规则？", Arrays.asList("同意", "不同意"), 20));
        m.put("questions", qs); return m;
    }

    static Map<String, Object> mkQ(int id, String type, String question, List<String> options, int score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("type", type); m.put("question", question);
        if (options != null) m.put("options", new ArrayList<>(options));
        m.put("score", (long) score); return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadMap(String file, Map<String, Object> def) {
        try { String s = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            if (s.trim().isEmpty()) return def != null ? def : new LinkedHashMap<>();
            Object o = parseJson(s); return o instanceof Map ? (Map<String, Object>) o : (def != null ? def : new LinkedHashMap<>());
        } catch (Exception e) { return def != null ? new LinkedHashMap<>(def) : new LinkedHashMap<>(); }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> loadList(String file) {
        try { String s = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            if (s.trim().isEmpty()) return new ArrayList<>();
            Object o = parseJson(s); return o instanceof List ? (List<Map<String, Object>>) o : new ArrayList<>();
        } catch (Exception e) { return new ArrayList<>(); }
    }

    static synchronized void saveAll() {
        writeJson(CONFIG_FILE, config); writeJson(USERS_FILE, users);
        writeJson(QUEST_FILE, questionnaire); writeJson(APP_FILE, applications);
        writeJson(IMG_META_FILE, imagesMeta);
        writeJson(APPROVED_QQ_FILE, new ArrayList<>(approvedQQs));
    }

    static void writeJson(String file, Object obj) {
        try { Files.write(Paths.get(file), toJson(obj).getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { e.printStackTrace(); }
    }

    static String sha256(String s) {
        try { byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); for (byte b : h) sb.append(String.format("%02x", b)); return sb.toString();
        } catch (Exception e) { return s; }
    }

    static String rconExec(String cmd) {
        String host = str(config, "rconHost"); int port = (int) lng(config, "rconPort"); String pass = str(config, "rconPassword");
        if (host.isEmpty() || pass.isEmpty()) return "[未配置RCON]";
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 3000); sock.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());
            sendRcon(out, -1, 3, pass); int[] resp = recvRcon(in);
            if (resp[0] == -1) return "[RCON认证失败]";
            sendRcon(out, 1, 2, cmd); int[] r2 = recvRcon(in);
            String result = r2[2] == 0 ? "" : readRconStr(in, r2[2]);
            try { in.mark(100); int b = in.read(); if (b >= 0) { in.reset(); int[] r3 = recvRcon(in); if (r3[2] > 0) result += readRconStr(in, r3[2]); } } catch (Exception ignored) {}
            return result.trim();
        } catch (Exception e) { return "[RCON连接失败: " + e.getMessage() + "]"; }
    }

    static void sendRcon(DataOutputStream out, int id, int type, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        out.writeInt(Integer.reverseBytes(4 + 4 + body.length + 2));
        out.writeInt(Integer.reverseBytes(id)); out.writeInt(Integer.reverseBytes(type));
        out.write(body); out.write(0); out.write(0); out.flush();
    }

    static int[] recvRcon(DataInputStream in) throws IOException {
        int len = Integer.reverseBytes(in.readInt()); int id = Integer.reverseBytes(in.readInt());
        int type = Integer.reverseBytes(in.readInt()); int bodyLen = len - 10;
        if (bodyLen > 0) in.skipBytes(bodyLen); in.readByte(); in.readByte();
        return new int[]{id, type, bodyLen};
    }

    static String readRconStr(DataInputStream in, int len) throws IOException {
        byte[] buf = new byte[len]; in.readFully(buf);
        return new String(buf, 0, len > 0 && buf[len - 1] == 0 ? len - 1 : len, StandardCharsets.UTF_8);
    }

    static String napcatPost(String api, String body) {
        String host = str(config, "napcatHost"); int port = (int) lng(config, "napcatPort");
        if (host.isEmpty()) return null;
        try { URL url = new URL("http://" + host + ":" + port + api);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); conn.setDoOutput(true);
            conn.setConnectTimeout(3000); conn.setReadTimeout(3000);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[1024]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    return bos.toString(StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {} return null;
    }

    static void napcatApproveGroup(String flag) {
        long groupId = lng(config, "qqGroup"); if (groupId == 0) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group_id", groupId); m.put("flag", flag);
        m.put("sub_type", "add"); m.put("approve", true);
        napcatPost("/set_group_add_request", toJson(m));
    }

    static void napcatSendGroup(String msg) {
        long groupId = lng(config, "qqGroup"); if (groupId == 0) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group_id", groupId); m.put("message", msg);
        napcatPost("/send_group_msg", toJson(m));
    }

    static String newSession(boolean isAdmin) {
        String token = UUID.randomUUID().toString().replace("-", "") + Long.toHexString(System.currentTimeMillis());
        sessionExpiry.put(token, System.currentTimeMillis() + SESSION_TIMEOUT);
        if (isAdmin) adminSessions.put(token, "1"); else userSessions.put(token, "1");
        return token;
    }

    static String getSessionCookie(HttpExchange t, boolean isAdmin) {
        String cookie = t.getRequestHeaders().getFirst("Cookie"); if (cookie == null) return null;
        for (String part : cookie.split(";")) { part = part.trim();
            String prefix = isAdmin ? "admin_token=" : "user_token=";
            if (part.startsWith(prefix)) { String token = part.substring(prefix.length());
                Long exp = sessionExpiry.get(token);
                if (exp != null && System.currentTimeMillis() < exp) return token;
                else { if (isAdmin) adminSessions.remove(token); else userSessions.remove(token); sessionExpiry.remove(token); }
            } } return null;
    }

    static String getUserBySession(HttpExchange t) { String token = getSessionCookie(t, false); return token != null ? userSessions.get(token) : null; }
    static boolean isAdminSession(HttpExchange t) { return getSessionCookie(t, true) != null; }
    static void setCookie(HttpExchange t, String name, String value) { t.getResponseHeaders().add("Set-Cookie", name + "=" + value + "; Path=/; Max-Age=86400; HttpOnly"); }
    static void clearCookie(HttpExchange t, String name) { t.getResponseHeaders().add("Set-Cookie", name + "=; Path=/; Max-Age=0; HttpOnly"); }

    static void redirect(HttpExchange t, String url) throws IOException { t.getResponseHeaders().add("Location", url); t.sendResponseHeaders(302, -1); }
    static void servePage(HttpExchange t, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(200, bytes.length); t.getResponseBody().write(bytes); t.getResponseBody().close();
    }
    static void serveImage(HttpExchange t) throws IOException {
        String path = t.getRequestURI().getPath(); String filename = path.substring("/images/".length()).replace("/", "");
        if (filename.isEmpty()) { t.sendResponseHeaders(404, -1); return; }
        File f = new File(IMG_DIR, filename);
        if (!f.exists()) { t.sendResponseHeaders(404, -1); return; }
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        String ct = "image/png";
        if (ext.equals("jpg") || ext.equals("jpeg")) ct = "image/jpeg";
        else if (ext.equals("gif")) ct = "image/gif"; else if (ext.equals("webp")) ct = "image/webp";
        byte[] data = Files.readAllBytes(f.toPath());
        t.getResponseHeaders().add("Content-Type", ct); t.sendResponseHeaders(200, data.length);
        t.getResponseBody().write(data); t.getResponseBody().close();
    }
    static String readBody(HttpExchange t) throws IOException {
        try (InputStream is = t.getRequestBody(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }
    static void sendJson(HttpExchange t, int code, Object obj) throws IOException {
        String json = toJson(obj); byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        t.sendResponseHeaders(code, bytes.length); t.getResponseBody().write(bytes); t.getResponseBody().close();
    }
    static Map<String, Object> ok() { Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", true); return m; }
    static Map<String, Object> err(String msg) { Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", false); m.put("msg", msg); return m; }

    static String CSS = "*{margin:0;padding:0;box-sizing:border-box}"
        + "body{font-family:system-ui,-apple-system,sans-serif;background:#0f0f1a;color:#e0e0e0;min-height:100vh;line-height:1.6}"
        + "a{color:#4ade80;text-decoration:none}a:hover{color:#22c55e}"
        + ".container{max-width:1000px;margin:0 auto;padding:20px}"
        + ".nav{background:#1a1a2e;padding:12px 20px;display:flex;gap:16px;align-items:center;border-bottom:1px solid #2a2a4a;flex-wrap:wrap}"
        + ".nav .brand{font-weight:700;font-size:18px;color:#4ade80;margin-right:auto}"
        + ".nav a{color:#aaa;padding:6px 12px;border-radius:6px;font-size:14px;transition:.2s}"
        + ".nav a:hover,.nav a.active{background:#2a2a4a;color:#fff}"
        + ".card{background:#1a1a2e;border:1px solid #2a2a4a;border-radius:12px;padding:24px;margin:16px 0}"
        + ".card h2{margin-bottom:16px;color:#4ade80;font-size:20px}"
        + ".card h3{margin-bottom:12px;color:#ccc;font-size:16px}"
        + ".form-group{margin-bottom:16px}"
        + ".form-group label{display:block;margin-bottom:6px;font-size:14px;color:#aaa}"
        + ".form-group input,.form-group textarea,.form-group select{width:100%;padding:10px 14px;background:#0f0f1a;border:1px solid #2a2a4a;border-radius:8px;color:#e0e0e0;font-size:14px;outline:none;transition:.2s}"
        + ".form-group input:focus,.form-group textarea:focus,.form-group select:focus{border-color:#4ade80}"
        + ".form-group textarea{min-height:80px;resize:vertical}"
        + ".btn{display:inline-block;padding:10px 24px;border:none;border-radius:8px;font-size:14px;font-weight:600;cursor:pointer;transition:.2s;text-align:center}"
        + ".btn-primary{background:#4ade80;color:#0f0f1a}.btn-primary:hover{background:#22c55e}"
        + ".btn-danger{background:#ef4444;color:#fff}.btn-danger:hover{background:#dc2626}"
        + ".btn-secondary{background:#2a2a4a;color:#e0e0e0}.btn-secondary:hover{background:#3a3a5a}"
        + ".btn-sm{padding:6px 14px;font-size:12px}"
        + ".btn-block{display:block;width:100%}"
        + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:16px}"
        + ".stat{text-align:center;padding:20px}.stat .num{font-size:32px;font-weight:700;color:#4ade80}.stat .label{font-size:13px;color:#888;margin-top:4px}"
        + ".img-card{border-radius:10px;overflow:hidden;border:1px solid #2a2a4a;background:#1a1a2e}"
        + ".img-card img{width:100%;height:180px;object-fit:cover;display:block}"
        + ".img-card .desc{padding:10px 14px;font-size:13px;color:#aaa}"
        + "table{width:100%;border-collapse:collapse;font-size:14px}"
        + "th,td{padding:10px 12px;text-align:left;border-bottom:1px solid #2a2a4a}"
        + "th{color:#4ade80;font-weight:600;background:#12122a}"
        + ".badge{display:inline-block;padding:2px 10px;border-radius:20px;font-size:12px;font-weight:600}"
        + ".badge-pending{background:#eab30833;color:#eab308}.badge-approved{background:#4ade8033;color:#4ade80}"
        + ".badge-rejected{background:#ef444433;color:#ef4444}"
        + ".score-input{width:60px;padding:4px 8px;background:#0f0f1a;border:1px solid #2a2a4a;border-radius:6px;color:#e0e0e0;font-size:14px;text-align:center}"
        + ".toast{position:fixed;top:20px;right:20px;padding:12px 24px;border-radius:8px;font-size:14px;z-index:9999;animation:fadeIn .3s}"
        + ".toast-success{background:#4ade80;color:#0f0f1a}.toast-error{background:#ef4444;color:#fff}"
        + "@keyframes fadeIn{from{opacity:0;transform:translateY(-10px)}to{opacity:1;transform:translateY(0)}}"
        + ".login-box{max-width:400px;margin:80px auto}"
        + ".empty{text-align:center;padding:40px;color:#666}"
        + ".quest-item{background:#12122a;border-radius:8px;padding:16px;margin-bottom:12px}"
        + ".quest-item .qhead{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}"
        + ".quest-item .qtype{font-size:11px;padding:2px 8px;border-radius:10px;background:#2a2a4a;color:#888}"
        + ".radio-group{display:flex;gap:12px;flex-wrap:wrap;margin-top:6px}"
        + ".radio-group label{display:flex;align-items:center;gap:6px;font-size:14px;color:#ccc;cursor:pointer}"
        + ".radio-group input[type=radio]{accent-color:#4ade80}"
        + ".actions-bar{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}"
        + ".checkbox-group{display:flex;align-items:center;gap:8px;font-size:14px;cursor:pointer}"
        + ".checkbox-group input{accent-color:#4ade80;width:18px;height:18px}"
        + ".ans-text{color:#ccc;padding:8px 12px;background:#0f0f1a;border-radius:6px;margin-top:4px;font-size:14px;white-space:pre-wrap}"
        + ".total-score{font-size:24px;font-weight:700;color:#4ade80;margin:12px 0}"
        + ".review-section{margin-bottom:20px;padding-bottom:20px;border-bottom:1px solid #2a2a4a}"
        + ".review-section:last-child{border-bottom:none}";

    static String page(String title, String nav, String content) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>" + title + " - 白名单系统</title><style>" + CSS + "</style></head><body>"
            + nav + "<div class='container'>" + content + "</div>"
            + "<script>function toast(msg,ok){var d=document.createElement('div');d.className='toast toast-'+(ok?'success':'error');d.textContent=msg;document.body.appendChild(d);setTimeout(function(){d.remove()},3000);}</script></body></html>";
    }

    static String userNav(String active) {
        return "<div class='nav'><span class='brand'>白名单系统</span>"
            + navLink("/user/dashboard", "首页", active) + navLink("/user/apply", "申请白名单", active)
            + navLink("/user/status", "我的申请", active) + navLink("/admin/login", "管理后台", "") + "</div>";
    }

    static String adminNav(String active) {
        return "<div class='nav'><span class='brand'>管理后台</span>"
            + navLink("/admin/dashboard", "概览", active) + navLink("/admin/settings", "系统设置", active)
            + navLink("/admin/questionnaire", "问卷管理", active) + navLink("/admin/images", "图片管理", active)
            + navLink("/admin/review", "审核管理", active) + navLink("/user/login", "返回前台", "") + "</div>";
    }

    static String navLink(String href, String text, String active) {
        return "<a href='" + href + "'" + (href.equals(active) ? " class='active'" : "") + ">" + text + "</a>";
    }

    static String userLoginPage() {
        return page("用户登录", userNav(""),
            "<div class='login-box'><div class='card' style='text-align:center'>"
            + "<h2 style='margin-bottom:24px'>用户登录</h2>"
            + "<div class='form-group'><label>游戏名</label><input id='gn' placeholder='输入你的游戏ID'></div>"
            + "<div class='form-group'><label>QQ号</label><input id='qq' type='number' placeholder='输入你的QQ号'></div>"
            + "<button class='btn btn-primary btn-block' onclick='login()'>登录 / 注册</button>"
            + "<p style='margin-top:16px;font-size:13px;color:#666'>首次登录将自动注册账号</p>"
            + "</div></div>"
            + "<script>function login(){var gn=document.getElementById('gn').value.trim(),qq=document.getElementById('qq').value.trim();"
            + "if(!gn||!qq)return toast('请填写完整信息',false);"
            + "fetch('/api/user/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({gameName:gn,qq:qq})})"
            + ".then(function(r){return r.json()}).then(function(d){if(d.ok)location.href='/user/dashboard';else toast(d.msg,false)}).catch(function(){toast('网络错误',false)});}</script>");
    }

    static String userDashboardPage(HttpExchange t) {
        String user = getUserBySession(t);
        if (user == null) return redirectPage("/user/login");
        StringBuilder imgs = new StringBuilder();
        for (Map<String, Object> img : imagesMeta) {
            imgs.append("<div class='img-card'><img src='/images/" + esc(str(img, "filename")) + "' alt=''>"
                + "<div class='desc'>" + esc(str(img, "description")) + "</div></div>");
        }
        if (imgs.length() == 0) imgs.append("<div class='empty'>暂无服务器图片</div>");
        return page("用户首页", userNav("/user/dashboard"),
            "<div class='card'><h2>欢迎，" + esc(user) + "</h2>"
            + "<p style='color:#aaa;margin-bottom:16px'>" + esc(str(config, "serverDescription")) + "</p>"
            + "<a href='/user/apply' class='btn btn-primary'>申请白名单</a>"
            + "<a href='/user/status' class='btn btn-secondary' style='margin-left:8px'>查看申请状态</a>"
            + "</div>"
            + "<div class='card'><h2>服务器风采</h2><div class='grid'>" + imgs.toString() + "</div></div>");
    }

    @SuppressWarnings("unchecked")
    static String userApplyPage(HttpExchange t) {
        String user = getUserBySession(t);
        if (user == null) return redirectPage("/user/login");
        for (Map<String, Object> app : applications) {
            if (str(app, "gameName").equals(user)) {
                String st = str(app, "status");
                if ("pending".equals(st)) return page("申请白名单", userNav("/user/apply"),
                    "<div class='card'><h2>申请白名单</h2><div class='empty'>你已提交申请，正在审核中，请耐心等待。</div>"
                    + "<a href='/user/status' class='btn btn-secondary'>查看详情</a></div>");
                if ("approved".equals(st)) return page("申请白名单", userNav("/user/apply"),
                    "<div class='card'><h2>申请白名单</h2><div class='empty' style='color:#4ade80'>你已通过白名单审核！</div></div>");
            }
        }
        List<Map<String, Object>> qs = (List<Map<String, Object>>) questionnaire.getOrDefault("questions", new ArrayList<>());
        StringBuilder form = new StringBuilder("<form id='applyForm'>");
        for (Map<String, Object> q : qs) {
            String type = str(q, "type");
            form.append("<div class='quest-item'><div class='qhead'><strong>" + esc(str(q, "question")) + "</strong>"
                + "<span class='qtype'>" + ("choice".equals(type) ? "选择题" : "简答题") + "（" + lng(q, "score") + "分）</span></div>");
            if ("choice".equals(type)) {
                List<String> opts = (List<String>) q.getOrDefault("options", new ArrayList<>());
                form.append("<div class='radio-group'>");
                for (int i = 0; i < opts.size(); i++) {
                    form.append("<label><input type='radio' name='q_" + lng(q, "id") + "' value='" + esc(opts.get(i)) + "'>" + esc(opts.get(i)) + "</label>");
                }
                form.append("</div>");
            } else {
                form.append("<div class='form-group'><textarea name='q_" + lng(q, "id") + "' placeholder='请输入你的回答'></textarea></div>");
            }
            form.append("</div>");
        }
        form.append("<div class='form-group'><label class='checkbox-group'><input type='checkbox' id='wantGroup' checked> 申请加入服务器QQ群</label></div>");
        form.append("<button type='button' class='btn btn-primary btn-block' onclick='submitApply()'>提交申请</button></form>");
        return page("申请白名单", userNav("/user/apply"),
            "<div class='card'><h2>" + esc(str(questionnaire, "title")) + "</h2>" + form.toString() + "</div>"
            + "<script>function submitApply(){var fd=new FormData(document.getElementById('applyForm'));var ans={};fd.forEach(function(v,k){ans[k]=v});"
            + "ans.wantJoinGroup=document.getElementById('wantGroup').checked;"
            + "fetch('/api/user/apply',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(ans)})"
            + ".then(function(r){return r.json()}).then(function(d){if(d.ok){toast('提交成功！',true);setTimeout(function(){location.href='/user/status'},1000);}else toast(d.msg,false)}).catch(function(){toast('网络错误',false)});}</script>");
    }

    @SuppressWarnings("unchecked")
    static String userStatusPage(HttpExchange t) {
        String user = getUserBySession(t);
        if (user == null) return redirectPage("/user/login");
        Map<String, Object> myApp = null;
        for (Map<String, Object> app : applications) { if (str(app, "gameName").equals(user)) { myApp = app; break; } }
        if (myApp == null) return page("我的申请", userNav("/user/status"),
            "<div class='card'><h2>我的申请</h2><div class='empty'>你还没有提交过申请</div>"
            + "<a href='/user/apply' class='btn btn-primary'>去申请</a></div>");
        String st = str(myApp, "status");
        String badgeClass = "pending".equals(st) ? "badge-pending" : ("approved".equals(st) ? "badge-approved" : "badge-rejected");
        String stText = "pending".equals(st) ? "待审核" : ("approved".equals(st) ? "已通过" : "已拒绝");
        String scoreText = myApp.containsKey("score") && myApp.get("score") != null ? "得分: " + lng(myApp, "score") + " / 及格线 " + lng(config, "passScore") : "";
        String reviewNote = str(myApp, "reviewNote");
        StringBuilder detail = new StringBuilder();
        if (myApp.containsKey("answers")) {
            Map<String, Object> answers = (Map<String, Object>) myApp.get("answers");
            List<Map<String, Object>> qs = (List<Map<String, Object>>) questionnaire.getOrDefault("questions", new ArrayList<>());
            for (Map<String, Object> q : qs) {
                String qid = "q_" + lng(q, "id");
                String ans = answers.containsKey(qid) ? str(answers, qid) : "未作答";
                detail.append("<tr><td>" + esc(str(q, "question")) + "</td><td>" + esc(ans) + "</td></tr>");
            }
        }
        return page("我的申请", userNav("/user/status"),
            "<div class='card'><h2>我的申请</h2>"
            + "<p style='margin-bottom:12px'>状态: <span class='badge " + badgeClass + "'>" + stText + "</span></p>"
            + (scoreText.isEmpty() ? "" : "<p style='margin-bottom:12px;color:#aaa'>" + scoreText + "</p>")
            + (!reviewNote.isEmpty() ? "<p style='margin-bottom:12px;color:#eab308'>管理员备注: " + esc(reviewNote) + "</p>" : "")
            + "<p style='color:#888;font-size:13px'>提交时间: " + esc(str(myApp, "submittedAt")) + "</p></div>"
            + "<div class='card'><h3>我的回答</h3><table><tr><th>问题</th><th>回答</th></tr>" + detail.toString() + "</table></div>");
    }

    static String redirectPage(String url) { return "<script>location.href='" + url + "';</script>"; }

    static String adminLoginPage() {
        return page("管理员登录", adminNav(""),
            "<div class='login-box'><div class='card' style='text-align:center'>"
            + "<h2 style='margin-bottom:24px'>管理员登录</h2>"
            + "<div class='form-group'><label>用户名</label><input id='un' placeholder='管理员用户名'></div>"
            + "<div class='form-group'><label>密码</label><input id='pw' type='password' placeholder='管理员密码'></div>"
            + "<button class='btn btn-primary btn-block' onclick='login()'>登录</button>"
            + "</div></div>"
            + "<script>function login(){var u=document.getElementById('un').value.trim(),p=document.getElementById('pw').value.trim();"
            + "if(!u||!p)return toast('请填写完整信息',false);"
            + "fetch('/api/admin/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:p})})"
            + ".then(function(r){return r.json()}).then(function(d){if(d.ok)location.href='/admin/dashboard';else toast(d.msg,false)}).catch(function(){toast('网络错误',false)});}</script>");
    }

    static String adminDashboardPage(HttpExchange t) {
        if (!isAdminSession(t)) return redirectPage("/admin/login");
        long pending = 0, approved = 0, rejected = 0;
        for (Map<String, Object> a : applications) {
            String st = str(a, "status");
            if ("pending".equals(st)) pending++; else if ("approved".equals(st)) approved++; else rejected++;
        }
        return page("管理概览", adminNav("/admin/dashboard"),
            "<div class='grid'>"
            + "<div class='card stat'><div class='num'>" + users.size() + "</div><div class='label'>注册用户</div></div>"
            + "<div class='card stat'><div class='num' style='color:#eab308'>" + pending + "</div><div class='label'>待审核</div></div>"
            + "<div class='card stat'><div class='num'>" + approved + "</div><div class='label'>已通过</div></div>"
            + "<div class='card stat'><div class='num' style='color:#ef4444'>" + rejected + "</div><div class='label'>已拒绝</div></div>"
            + "</div>"
            + "<div class='card'><h2>快速操作</h2>"
            + "<a href='/admin/review' class='btn btn-primary'>审核申请</a>"
            + "<a href='/admin/questionnaire' class='btn btn-secondary' style='margin-left:8px'>编辑问卷</a>"
            + "<a href='/admin/images' class='btn btn-secondary' style='margin-left:8px'>管理图片</a>"
            + "<a href='/admin/settings' class='btn btn-secondary' style='margin-left:8px'>系统设置</a>"
            + "</div>"
            + "<div class='card'><h2>RCON 测试</h2>"
            + "<div style='display:flex;gap:8px'><input id='rcmd' style='flex:1;padding:10px 14px;background:#0f0f1a;border:1px solid #2a2a4a;border-radius:8px;color:#e0e0e0' placeholder='输入MC命令，如 list'>"
            + "<button class='btn btn-secondary' onclick='testRcon()'>执行</button></div>"
            + "<div id='rresult' style='margin-top:12px;padding:12px;background:#0f0f1a;border-radius:8px;font-family:monospace;font-size:13px;color:#888;display:none'></div>"
            + "</div>"
            + "<script>function testRcon(){var cmd=document.getElementById('rcmd').value.trim();if(!cmd)return;"
            + "fetch('/api/admin/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({testRcon:cmd})})"
            + ".then(function(r){return r.json()}).then(function(d){var el=document.getElementById('rresult');el.style.display='block';el.textContent=d.msg||d.result||'无返回';}).catch(function(){var el=document.getElementById('rresult');el.style.display='block';el.textContent='请求失败';});}</script>");
    }

    static String adminSettingsPage(HttpExchange t) {
        if (!isAdminSession(t)) return redirectPage("/admin/login");
        return page("系统设置", adminNav("/admin/settings"),
            "<div class='card'><h2>管理员账号</h2>"
            + "<div class='form-group'><label>用户名</label><input id='aun' value='" + esc(str(config, "adminUsername")) + "'></div>"
            + "<div class='form-group'><label>新密码（留空不修改）</label><input id='apw' type='password' placeholder='输入新密码'></div>"
            + "</div>"
            + "<div class='card'><h2>RCON 设置</h2>"
            + "<div class='form-group'><label>服务器IP</label><input id='rh' value='" + esc(str(config, "rconHost")) + "'></div>"
            + "<div class='form-group'><label>RCON端口</label><input id='rp' type='number' value='" + lng(config, "rconPort") + "'></div>"
            + "<div class='form-group'><label>RCON密码</label><input id='rpw' value='" + esc(str(config, "rconPassword")) + "'></div>"
            + "</div>"
            + "<div class='card'><h2>NapCat 设置</h2>"
            + "<div class='form-group'><label>NapCat IP</label><input id='nh' value='" + esc(str(config, "napcatHost")) + "'></div>"
            + "<div class='form-group'><label>NapCat 端口</label><input id='np' type='number' value='" + lng(config, "napcatPort") + "'></div>"
            + "<div class='form-group'><label>生效QQ群号</label><input id='nq' type='number' value='" + lng(config, "qqGroup") + "'></div>"
            + "<p style='font-size:12px;color:#666;margin-top:4px'>NapCat HTTP上报地址请设置为: <code style='color:#4ade80'>http://本系统IP:端口/api/napcat/webhook</code></p>"
            + "</div>"
            + "<div class='card'><h2>其他设置</h2>"
            + "<div class='form-group'><label>及格分数</label><input id='ps' type='number' value='" + lng(config, "passScore") + "'></div>"
            + "<div class='form-group'><label>服务器名称</label><input id='sn' value='" + esc(str(config, "serverName")) + "'></div>"
            + "<div class='form-group'><label>服务器描述</label><textarea id='sd'>" + esc(str(config, "serverDescription")) + "</textarea></div>"
            + "</div>"
            + "<button class='btn btn-primary' onclick='saveSettings()'>保存设置</button>"
            + "<script>function saveSettings(){"
            + "var d={adminUsername:document.getElementById('aun').value.trim(),"
            + "newPassword:document.getElementById('apw').value,rconHost:document.getElementById('rh').value.trim(),"
            + "rconPort:parseInt(document.getElementById('rp').value)||25575,rconPassword:document.getElementById('rpw').value,"
            + "napcatHost:document.getElementById('nh').value.trim(),napcatPort:parseInt(document.getElementById('np').value)||3000,"
            + "qqGroup:document.getElementById('nq').value.trim(),passScore:parseInt(document.getElementById('ps').value)||60,"
            + "serverName:document.getElementById('sn').value.trim(),serverDescription:document.getElementById('sd').value.trim()};"
            + "fetch('/api/admin/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)})"
            + ".then(function(r){return r.json()}).then(function(r){if(r.ok)toast('保存成功',true);else toast(r.msg,false)}).catch(function(){toast('网络错误',false)});}</script>");
    }

    @SuppressWarnings("unchecked")
    static String adminQuestPage(HttpExchange t) {
        if (!isAdminSession(t)) return redirectPage("/admin/login");
        List<Map<String, Object>> qs = (List<Map<String, Object>>) questionnaire.getOrDefault("questions", new ArrayList<>());
        StringBuilder qlist = new StringBuilder();
        for (int i = 0; i < qs.size(); i++) {
            Map<String, Object> q = qs.get(i);
            String type = str(q, "type");
            qlist.append("<div class='quest-item' id='qi_" + i + "'><div class='qhead'><strong>" + esc(str(q, "question")) + "</strong>"
                + "<span class='qtype'>" + ("choice".equals(type) ? "选择题" : "简答题") + " · " + lng(q, "score") + "分</span></div>");
            if ("choice".equals(type)) {
                List<String> opts = (List<String>) q.getOrDefault("options", new ArrayList<>());
                qlist.append("<div style='color:#888;font-size:13px'>选项: " + String.join(" / ", opts) + "</div>");
            }
            qlist.append("<div class='actions-bar'>"
                + "<button class='btn btn-secondary btn-sm' onclick='editQ(" + i + ")'>编辑</button>"
                + "<button class='btn btn-danger btn-sm' onclick='delQ(" + i + ")'>删除</button>"
                + (i > 0 ? "<button class='btn btn-secondary btn-sm' onclick='moveQ(" + i + ",-1)'>上移</button>" : "")
                + (i < qs.size() - 1 ? "<button class='btn btn-secondary btn-sm' onclick='moveQ(" + i + ",1)'>下移</button>" : "")
                + "</div></div>");
        }
        if (qlist.length() == 0) qlist.append("<div class='empty'>暂无题目，请添加</div>");
        String qsJson = toJson(questionnaire.getOrDefault("questions", new ArrayList<>()));
        return page("问卷管理", adminNav("/admin/questionnaire"),
            "<div class='card'><h2>问卷标题</h2>"
            + "<input id='qtitle' value='" + esc(str(questionnaire, "title")) + "' style='width:100%;padding:10px 14px;background:#0f0f1a;border:1px solid #2a2a4a;border-radius:8px;color:#e0e0e0;font-size:16px'>"
            + "</div>"
            + "<div class='card'><h2>题目列表</h2><div id='qlist'>" + qlist.toString() + "</div></div>"
            + "<div class='card'><h2>添加/编辑题目</h2>"
            + "<div class='form-group'><label>题目类型</label><select id='nqtype' onchange='toggleOpts()'><option value='choice'>选择题</option><option value='text'>简答题</option></select></div>"
            + "<div class='form-group'><label>题目内容</label><input id='nquestion' placeholder='输入题目'></div>"
            + "<div id='optBox'><div class='form-group'><label>选项（每行一个）</label><textarea id='noptions' rows='4' placeholder='选项1\\n选项2\\n选项3'></textarea></div></div>"
            + "<div class='form-group'><label>分值</label><input id='nscore' type='number' value='10'></div>"
            + "<input id='editIdx' type='hidden' value='-1'>"
            + "<button class='btn btn-primary' onclick='addQ()'>添加题目</button>"
            + "</div>"
            + "<button class='btn btn-primary' style='margin-top:16px' onclick='saveQuest()'>保存问卷</button>"
            + "<script>"
            + "var tempQs=" + qsJson + ";"
            + "function toggleOpts(){document.getElementById('optBox').style.display=document.getElementById('nqtype').value==='choice'?'block':'none'}"
            + "function renderQs(){var h='';for(var i=0;i<tempQs.length;i++){var q=tempQs[i];var tp=q.type==='choice'?'选择题':'简答题';"
            + "h+='<div class=\"quest-item\"><div class=\"qhead\"><strong>'+q.question+'</strong><span class=\"qtype\">'+tp+' · '+q.score+'分</span></div>';"
            + "if(q.type==='choice'&&q.options)h+='<div style=\"color:#888;font-size:13px\">选项: '+q.options.join(' / ')+'</div>';"
            + "h+='<div class=\"actions-bar\"><button class=\"btn btn-secondary btn-sm\" onclick=\"editQ('+i+')\">编辑</button>';"
            + "h+='<button class=\"btn btn-danger btn-sm\" onclick=\"delQ('+i+')\">删除</button>';"
            + "if(i>0)h+='<button class=\"btn btn-secondary btn-sm\" onclick=\"moveQ('+i+',-1)\">上移</button>';"
            + "if(i<tempQs.length-1)h+='<button class=\"btn btn-secondary btn-sm\" onclick=\"moveQ('+i+',1)\">下移</button>';"
            + "h+='</div></div>';} return h;}"
            + "function refreshQs(){document.getElementById('qlist').innerHTML=renderQs();}"
            + "function addQ(){var tp=document.getElementById('nqtype').value,q=document.getElementById('nquestion').value.trim(),"
            + "s=parseInt(document.getElementById('nscore').value)||10,opts=[];"
            + "if(tp==='choice'){opts=document.getElementById('noptions').value.trim().split('\\n').map(function(x){return x.trim()}).filter(function(x){return x});"
            + "if(opts.length<2)return toast('选择题至少需要2个选项',false)}"
            + "if(!q)return toast('请输入题目',false);var idx=parseInt(document.getElementById('editIdx').value);"
            + "var obj={id:idx>=0?tempQs[idx].id:(tempQs.length>0?tempQs[tempQs.length-1].id+1:1),type:tp,question:q,score:s};"
            + "if(tp==='choice')obj.options=opts;if(idx>=0)tempQs[idx]=obj;else tempQs.push(obj);"
            + "document.getElementById('nquestion').value='';document.getElementById('noptions').value='';document.getElementById('editIdx').value=-1;"
            + "refreshQs();toast(idx>=0?'已更新':'已添加',true)}"
            + "function editQ(i){var q=tempQs[i];document.getElementById('nqtype').value=q.type;toggleOpts();"
            + "document.getElementById('nquestion').value=q.question;document.getElementById('nscore').value=q.score;"
            + "if(q.options)document.getElementById('noptions').value=q.options.join('\\n');document.getElementById('editIdx').value=i;toast('正在编辑，点击添加按钮保存',true)}"
            + "function delQ(i){tempQs.splice(i,1);refreshQs()}"
            + "function moveQ(i,d){var j=i+d;if(j<0||j>=tempQs.length)return;var t=tempQs[i];tempQs[i]=tempQs[j];tempQs[j]=t;refreshQs()}"
            + "function saveQuest(){fetch('/api/admin/questionnaire',{method:'POST',headers:{'Content-Type':'application/json'},"
            + "body:JSON.stringify({title:document.getElementById('qtitle').value.trim(),questions:tempQs})})"
            + ".then(function(r){return r.json()}).then(function(d){if(d.ok)toast('保存成功',true);else toast(d.msg,false)}).catch(function(){toast('网络错误',false)})}"
            + "toggleOpts();</script>");
    }

    static String adminImagesPage(HttpExchange t) {
        if (!isAdminSession(t)) return redirectPage("/admin/login");
        StringBuilder imgs = new StringBuilder("<div class='grid'>");
        for (Map<String, Object> img : imagesMeta) {
            imgs.append("<div class='img-card'><img src='/images/" + esc(str(img, "filename")) + "'>"
                + "<div class='desc'>" + esc(str(img, "description")) + "</div>"
                + "<div style='padding:8px 14px;display:flex;gap:6px'>"
                + "<input class='img-desc-input' data-id='" + lng(img, "id") + "' value='" + esc(str(img, "description")) + "' style='flex:1;padding:4px 8px;background:#0f0f1a;border:1px solid #2a2a4a;border-radius:6px;color:#e0e0e0;font-size:12px'>"
                + "<button class='btn btn-secondary btn-sm' onclick='updateDesc(" + lng(img, "id") + ",this)'>保存</button>"
                + "<button class='btn btn-danger btn-sm' onclick='delImg(" + lng(img, "id") + ",\"" + esc(str(img, "filename")) + "\")'>删除</button>"
                + "</div></div>");
        }
        imgs.append("</div>");
        if (imagesMeta.isEmpty()) imgs = new StringBuilder("<div class='empty'>暂无图片</div>");
        return page("图片管理", adminNav("/admin/images"),
            "<div class='card'><h2>上传图片</h2>"
            + "<input type='file' id='imgFile' accept='image/*' style='margin-bottom:12px'>"
            + "<div class='form-group'><label>图片描述</label><input id='imgDesc' placeholder='输入图片描述'></div>"
            + "<button class='btn btn-primary' onclick='uploadImg()'>上传</button>"
            + "</div>"
            + "<div class='card'><h2>已有图片</h2>" + imgs.toString() + "</div>"
            + "<script>function uploadImg(){var f=document.getElementById('imgFile').files[0];if(!f)return toast('请选择图片',false);"
            + "var r=new FileReader();r.onload=function(){fetch('/api/admin/images',{method:'POST',headers:{'Content-Type':'application/json'},"
            + "body:JSON.stringify({action:'upload',filename:f.name,data:r.result.split(',')[1],description:document.getElementById('imgDesc').value.trim()})})"
            + ".then(function(r){return r.json()}).then(function(d){if(d.ok){toast('上传成功',true);setTimeout(function(){location.reload()},500)}else toast(d.msg,false)}).catch(function(){toast('网络错误',false)})};r.readAsDataURL(f)}"
            + "function updateDesc(id,btn){var inp=btn.previousElementSibling;fetch('/api/admin/images',{method:'POST',headers:{'Content-Type':'application/json'},"
            + "body:JSON.stringify({action:'updateDesc',id:id,description:inp.value.trim()})}).then(function(r){return r.json()}).then(function(d){if(d.ok)toast('已更新',true);else toast(d.msg,false)})"
            + "function delImg(id,fn){if(!confirm('确定删除？'))return;fetch('/api/admin/images',{method:'POST',headers:{'Content-Type':'application/json'},"
            + "body:JSON.stringify({action:'delete',id:id,filename:fn})}).then(function(r){return r.json()}).then(function(d){if(d.ok){toast('已删除',true);setTimeout(function(){location.reload()},500)}else toast(d.msg,false)})}</script>");
    }

    @SuppressWarnings("unchecked")
    static String adminReviewPage(HttpExchange t) {
        if (!isAdminSession(t)) return redirectPage("/admin/login");
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> a : applications) { if ("pending".equals(str(a, "status"))) pending.add(a); }
        if (pending.isEmpty()) return page("审核管理", adminNav("/admin/review"),
            "<div class='card'><h2>审核管理</h2><div class='empty'>没有待审核的申请</div></div>");
        List<Map<String, Object>> qs = (List<Map<String, Object>>) questionnaire.getOrDefault("questions", new ArrayList<>());
        StringBuilder sections = new StringBuilder();
        for (Map<String, Object> app : pending) {
            Map<String, Object> answers = app.containsKey("answers") ? (Map<String, Object>) app.get("answers") : new LinkedHashMap<>();
            sections.append("<div class='review-section'><h3 style='margin-bottom:4px'>" + esc(str(app, "gameName"))
                + " <span style='font-size:13px;color:#888'>QQ: " + esc(str(app, "qq")) + "</span></h3>"
                + "<p style='font-size:12px;color:#666;margin-bottom:12px'>提交时间: " + esc(str(app, "submittedAt"))
                + ("true".equals(str(app, "wantJoinGroup")) ? " · 申请加入QQ群" : "") + "</p>");
            long maxScore = 0;
            for (Map<String, Object> q : qs) {
                maxScore += lng(q, "score");
                String qid = "q_" + lng(q, "id");
                String ans = answers.containsKey(qid) ? str(answers, qid) : "未作答";
                sections.append("<div style='margin-bottom:12px'><div style='display:flex;justify-content:space-between;align-items:center'>"
                    + "<span style='font-size:14px'>" + esc(str(q, "question")) + "</span>"
                    + "<div style='display:flex;align-items:center;gap:6px'><span style='font-size:12px;color:#888'>满分" + lng(q, "score") + "</span>"
                    + "<input class='score-input' data-app='" + lng(app, "id") + "' data-qid='" + qid + "' data-max='" + lng(q, "score") + "' value='0' type='number' min='0' max='" + lng(q, "score") + "'></div></div>"
                    + "<div class='ans-text'>" + esc(ans) + "</div></div>");
            }
            sections.append("<div class='total-score' id='ts_" + lng(app, "id") + "'>总分: 0 / " + maxScore + "（及格: " + lng(config, "passScore") + "）</div>"
                + "<div class='form-group'><label>审核备注</label><input id='note_" + lng(app, "id") + "' placeholder='可选，给用户的备注'></div>"
                + "<div class='actions-bar'>"
                + "<button class='btn btn-primary' onclick='reviewApp(" + lng(app, "id") + ",true)'>通过</button>"
                + "<button class='btn btn-danger' onclick='reviewApp(" + lng(app, "id") + ",false)'>拒绝</button>"
                + "</div></div>");
        }
        return page("审核管理", adminNav("/admin/review"),
            "<div class='card'><h2>待审核申请 (" + pending.size() + ")</h2>" + sections.toString() + "</div>"
            + "<script>document.querySelectorAll('.score-input').forEach(function(inp){inp.addEventListener('input',updateTotal)});"
            + "function updateTotal(){document.querySelectorAll('.score-input').forEach(function(inp){var appId=inp.dataset.app;var all=document.querySelectorAll('.score-input[data-app=\"'+appId+'\"]');"
            + "var sum=0;all.forEach(function(x){sum+=parseInt(x.value)||0});var el=document.getElementById('ts_'+appId);if(el){var parts=el.textContent.split('/');el.textContent='总分: '+sum+' / '+parts[1]}})}"
            + "function reviewApp(id,approve){var scores={};document.querySelectorAll('.score-input[data-app=\"'+id+'\"]').forEach(function(inp){scores[inp.dataset.qid]=parseInt(inp.value)||0});"
            + "var note=document.getElementById('note_'+id).value.trim();"
            + "fetch('/api/admin/review',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:id,approve:approve,scores:scores,note:note})})"
            + ".then(function(r){return r.json()}).then(function(d){if(d.ok){toast(approve?'已通过':'已拒绝',true);setTimeout(function(){location.reload()},800)}else toast(d.msg,false)}).catch(function(){toast('网络错误',false)})}</script>");
    }

    static void apiUserLogin(HttpExchange t) throws IOException {
        Map<String, Object> body = (Map<String, Object>) parseJson(readBody(t));
        String gameName = str(body, "gameName").trim(); String qq = str(body, "qq").trim();
        if (gameName.isEmpty() || qq.isEmpty()) { sendJson(t, 400, err("请填写完整信息")); return; }
        if (gameName.length() > 16 || !qq.matches("\\d{5,11}")) { sendJson(t, 400, err("游戏名不超过16位，QQ号为5-11位数字")); return; }
        synchronized (WhitelistSystem.class) {
            Map<String, Object> user = null;
            for (Map<String, Object> u : users) { if (str(u, "gameName").equalsIgnoreCase(gameName)) { user = u; break; } }
            if (user == null) {
                user = new LinkedHashMap<>(); user.put("gameName", gameName); user.put("qq", qq);
                user.put("registeredAt", new Date().toString()); users.add(user);
            } else { user.put("qq", qq); }
            saveAll();
            String token = newSession(false); userSessions.put(token, gameName);
            setCookie(t, "user_token", token); sendJson(t, 200, ok());
        }
    }

    static void apiLogout(HttpExchange t, boolean isAdmin) throws IOException {
        String token = getSessionCookie(t, isAdmin);
        if (token != null) { if (isAdmin) adminSessions.remove(token); else userSessions.remove(token); sessionExpiry.remove(token); }
        clearCookie(t, isAdmin ? "admin_token" : "user_token"); sendJson(t, 200, ok());
    }

    @SuppressWarnings("unchecked")
    static void apiUserApply(HttpExchange t) throws IOException {
        String user = getUserBySession(t); if (user == null) { sendJson(t, 401, err("未登录")); return; }
        Map<String, Object> body = (Map<String, Object>) parseJson(readBody(t));
        synchronized (WhitelistSystem.class) {
            for (Map<String, Object> a : applications) {
                if (str(a, "gameName").equals(user)) {
                    String st = str(a, "status");
                    if ("pending".equals(st)) { sendJson(t, 400, err("你已有待审核的申请")); return; }
                    if ("approved".equals(st)) { sendJson(t, 400, err("你已通过白名单审核")); return; }
                }
            }
            String userQQ = ""; for (Map<String, Object> u : users) { if (str(u, "gameName").equals(user)) { userQQ = str(u, "qq"); break; } }
            Map<String, Object> app = new LinkedHashMap<>();
            app.put("id", applications.isEmpty() ? 1 : lng(applications.get(applications.size() - 1), "id") + 1);
            app.put("gameName", user); app.put("qq", userQQ);
            Map<String, Object> answers = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : body.entrySet()) { if (e.getKey().startsWith("q_")) answers.put(e.getKey(), e.getValue()); }
            if (answers.isEmpty() && body.containsKey("answers")) answers = (Map<String, Object>) body.get("answers");
            app.put("answers", answers);
            app.put("wantJoinGroup", body.containsKey("wantJoinGroup") && bool(body, "wantJoinGroup"));
            app.put("status", "pending"); app.put("submittedAt", new Date().toString());
            app.put("score", null); app.put("reviewNote", ""); app.put("reviewedAt", null);
            applications.add(app); saveAll(); sendJson(t, 200, ok());
        }
    }

    static void apiUserApplication(HttpExchange t) throws IOException {
        String user = getUserBySession(t); if (user == null) { sendJson(t, 401, err("未登录")); return; }
        synchronized (WhitelistSystem.class) {
            for (Map<String, Object> a : applications) { if (str(a, "gameName").equals(user)) { sendJson(t, 200, a); return; } }
        }
        sendJson(t, 200, err("暂无申请记录"));
    }

    static void apiAdminLogin(HttpExchange t) throws IOException {
        Map<String, Object> body = (Map<String, Object>) parseJson(readBody(t));
        String username = str(body, "username").trim(); String password = str(body, "password").trim();
        if (!username.equals(str(config, "adminUsername")) || !sha256(password).equals(str(config, "adminPassword"))) {
            sendJson(t, 401, err("用户名或密码错误")); return;
        }
        String token = newSession(true); setCookie(t, "admin_token", token); sendJson(t, 200, ok());
    }

    @SuppressWarnings("unchecked")
    static void apiAdminConfig(HttpExchange t) throws IOException {
        if (!isAdminSession(t)) { sendJson(t, 401, err("未登录")); return; }
        Map<String, Object> body = (Map<String, Object>) parseJson(readBody(t));
        if (body.containsKey("testRcon")) {
            String result = rconExec(str(body, "testRcon"));
            Map<String, Object> r = new LinkedHashMap<>(); r.put("ok", true); r.put("result", result);
            sendJson(t, 200, r); return;
        }
        synchronized (WhitelistSystem.class) {
            if (!str(body, "adminUsername").isEmpty()) config.put("adminUsername", str(body, "adminUsername"));
            if (!str(body, "newPassword").isEmpty()) config.put("adminPassword", sha256(str(body, "newPassword")));
            if (body.containsKey("rconHost")) config.put("rconHost", str(body, "rconHost"));
            if (body.containsKey("rconPort")) config.put("rconPort", lng(body, "rconPort"));
            if (body.containsKey("rconPassword")) config.put("rconPassword", str(body, "rconPassword"));
            if (body.containsKey("napcatHost")) config.put("napcatHost", str(body, "napcatHost"));
            if (body.containsKey("napcatPort")) config.put("napcatPort", lng(body, "napcatPort"));
            if (body.containsKey("qqGroup")) config.put("qqGroup", str(body, "qqGroup"));
            if (body.containsKey("passScore")) config.put("passScore", lng(body, "passScore"));
            if (body.containsKey("serverName")) config.put("serverName", str(body, "serverName"));
            if (body.containsKey("serverDescription")) config.put("serverDescription", str(body, "serverDescription"));
            saveAll(); sendJson(t, 200, ok());
        }
    }

    @SuppressWarnings("unchecked")
    static void apiAdminQuestionnaire(HttpExchange t) throws IOException {
        if (!isAdminSession(t)) { sendJson(t, 401, err("未登录")); return; }
        Map<String, Object> body = (Map<String, Object>) parseJson(readBody(t));
        synchronized (WhitelistSystem.class) {
            questionnaire.put("title", str(body, "title"));
            questionnaire.put("questions", body.getOrDefault("questions", new ArrayList<>()));
            saveAll(); sendJson(t, 200, ok());
        }
    }

    static void apiAdminApplications(HttpExchange t) throws IOException {
        if (!isAdminSession(t)) { sendJson(t, 401, err("未登录")); return; }
        synchronized (WhitelistSystem.class) { sendJson(t, 200, applications); }
    }

    @SuppressWarnings("unchecked")
    static void apiAdminReview(HttpExchange t) throws IOException {
        if (!isAdminSession(t)) { sendJson(t, 401, err("未登录")); return; }
        Map<String, Object> body = (Map<String, Object>) parseJson(readBody(t));
        long appId = lng(body, "id"); boolean approve = bool(body, "approve");
        Map<String, Object> scores = body.containsKey("scores") ? (Map<String, Object>) body.get("scores") : new LinkedHashMap<>();
        String note = str(body, "note");
        synchronized (WhitelistSystem.class) {
            Map<String, Object> app = null;
            for (Map<String, Object> a : applications) { if (lng(a, "id") == appId) { app = a; break; } }
            if (app == null) { sendJson(t, 404, err("申请不存在")); return; }
            if (!"pending".equals(str(app, "status"))) { sendJson(t, 400, err("该申请已审核")); return; }
            long totalScore = 0;
            for (Object v : scores.values()) { if (v instanceof Number) totalScore += ((Number) v).longValue(); }
            app.put("score", totalScore); app.put("reviewNote", note); app.put("reviewedAt", new Date().toString());
            long passScore = lng(config, "passScore"); String gameName = str(app, "gameName"); String qq = str(app, "qq");
            boolean wantGroup = bool(app, "wantJoinGroup");
            if (approve && totalScore >= passScore) {
                app.put("status", "approved");
                String rconResult = rconExec("whitelist add " + gameName);
                if (wantGroup && !qq.isEmpty() && lng(config, "qqGroup") > 0) {
                    approvedQQs.add(qq); napcatSendGroup("恭喜 " + gameName + " 通过白名单审核！欢迎加入服务器~");
                }
                Map<String, Object> result = ok(); result.put("rconResult", rconResult);
                saveAll(); sendJson(t, 200, result);
            } else if (approve && totalScore < passScore) {
                app.put("status", "rejected");
                app.put("reviewNote", (note.isEmpty() ? "" : note + "\n") + "[系统提示: 得分" + totalScore + "未达到及格线" + passScore + "]");
                saveAll(); sendJson(t, 200, err("得分" + totalScore + "未达到及格线" + passScore + "，已自动拒绝"));
            } else {
                app.put("status", "rejected"); saveAll(); sendJson(t, 200, ok());
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void apiAdminImages(HttpExchange t) throws IOException {
        if (!isAdminSession(t)) { sendJson(t, 401, err("未登录")); return; }
        Map<String, Object> body = (Map<String, Object>) parseJson(readBody(t));
        String action = str(body, "action");
        synchronized (WhitelistSystem.class) {
            if ("upload".equals(action)) {
                String filename = str(body, "filename"); String data = str(body, "data"); String desc = str(body, "description");
                if (filename.isEmpty() || data.isEmpty()) { sendJson(t, 400, err("请选择图片")); return; }
                String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : ".png";
                String safeName = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + ext;
                try {
                    byte[] imgData = Base64.getDecoder().decode(data);
                    Files.write(Paths.get(IMG_DIR, safeName), imgData);
                    long id = imagesMeta.isEmpty() ? 1 : lng(imagesMeta.get(imagesMeta.size() - 1), "id") + 1;
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("id", id); meta.put("filename", safeName); meta.put("description", desc);
                    imagesMeta.add(meta); saveAll(); sendJson(t, 200, ok());
                } catch (Exception e) { sendJson(t, 500, err("图片保存失败: " + e.getMessage())); }
            } else if ("delete".equals(action)) {
                long id = lng(body, "id"); String filename = str(body, "filename");
                imagesMeta.removeIf(m -> lng(m, "id") == id);
                try { Files.deleteIfExists(Paths.get(IMG_DIR, filename)); } catch (Exception ignored) {}
                saveAll(); sendJson(t, 200, ok());
            } else if ("updateDesc".equals(action)) {
                long id = lng(body, "id");
                for (Map<String, Object> m : imagesMeta) { if (lng(m, "id") == id) { m.put("description", str(body, "description")); break; } }
                saveAll(); sendJson(t, 200, ok());
            } else { sendJson(t, 400, err("未知操作")); }
        }
    }

    @SuppressWarnings("unchecked")
    static void apiNapcatWebhook(HttpExchange t) throws IOException {
        String body = readBody(t);
        try {
            Map<String, Object> event = (Map<String, Object>) parseJson(body);
            String postType = str(event, "post_type"); String reqType = str(event, "request_type");
            if ("request".equals(postType) && "group".equals(reqType)) {
                String userId = str(event, "user_id"); long groupId = lng(event, "group_id"); String flag = str(event, "flag");
                if (lng(config, "qqGroup") > 0 && groupId == lng(config, "qqGroup") && approvedQQs.contains(userId)) {
                    napcatApproveGroup(flag);
                    System.out.println("[NapCat] 自动批准QQ " + userId + " 加入群 " + groupId);
                }
            }
        } catch (Exception ignored) {}
        sendJson(t, 200, ok());
    }
}
