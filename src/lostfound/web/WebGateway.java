package lostfound.web;

import com.sun.net.httpserver.*;
import lostfound.corba.*;
import lostfound.corba.EventListener;
import org.omg.CORBA.ORB;
import org.omg.CORBA.SystemException;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Passerelle web : un petit serveur HTTP (inclus dans le JDK, pas de Python)
 * qui sert l'interface du dossier web/ et traduit chaque requête du navigateur
 * en appel CORBA vers le serveur LostFound.
 *
 *   Navigateur  --HTTP/JSON-->  WebGateway (client CORBA)  --IIOP-->  serveur LostFound
 *
 * Les autres PC du réseau n'ont besoin que d'un navigateur : http://<ip-du-serveur>:4173
 * Les notifications arrivent par callback CORBA (objet Relay) puis sont poussées
 * au navigateur (Server-Sent Events).
 *
 * Lancement : java lostfound.web.WebGateway -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 [--port 4173]
 */
public class WebGateway {

    private static ORB orb;
    private static volatile LostFoundService service;
    private static final Path WEB = Paths.get("web").toAbsolutePath().normalize();
    private static final List<OutputStream> sse = new CopyOnWriteArrayList<>();
    private static EventListener relayRef;                 // référence CORBA de notre objet Relay

    public static void main(String[] args) throws Exception {
        int port = 4173;
        for (int i = 0; i < args.length - 1; i++)
            if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);

        // 1. ORB du client. La passerelle est AUSSI serveur : elle héberge l'objet Relay
        //    que le serveur LostFound rappelle (callback). Il lui faut donc un POA.
        orb = ORB.init(args, null);
        POA rootPOA = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
        rootPOA.the_POAManager().activate();
        relayRef = EventListenerHelper.narrow(rootPOA.servant_to_reference(new Relay()));
        Thread orbThread = new Thread(orb::run, "orb");
        orbThread.setDaemon(true);
        orbThread.start();

        Thread watch = new Thread(WebGateway::watchServer, "corba-watch");
        watch.setDaemon(true);
        watch.start();

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 64);
        http.createContext("/api/", WebGateway::api);
        http.createContext("/", WebGateway::files);
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();

        System.out.println("==============================================");
        System.out.println("  LostFound - interface web prete");
        System.out.println("  Sur ce PC       : http://localhost:" + port);
        System.out.println("  Sur le reseau   : http://" + lanIp() + ":" + port);
        System.out.println("==============================================");
        // Garde la connexion SSE vivante (certains navigateurs coupent après 30 s de silence)
        while (true) {
            Thread.sleep(15000);
            broadcast(": ping\n\n");
        }
    }

    /**
     * 2. Retrouve le serveur dans le service de nommage (« LostFound »), puis s'abonne
     *    aux notifications en lui donnant la référence du Relay. Toutes les 3 s on vérifie
     *    que c'est toujours le même serveur (gère un redémarrage).
     */
    private static void watchServer() {
        LostFoundService subscribed = null;
        while (true) {
            try {
                NamingContextExt naming = NamingContextExtHelper.narrow(orb.resolve_initial_references("NameService"));
                LostFoundService s = LostFoundServiceHelper.narrow(naming.resolve_str("LostFound"));
                if (subscribed == null || !s._is_equivalent(subscribed)) {
                    s.subscribe(relayRef);
                    subscribed = s;
                    service = s;
                    System.out.println("[web] connecte au serveur CORBA \"LostFound\"");
                    broadcast("data: " + Json.write(Json.obj("kind", "reconnect")) + "\n\n");
                } else {
                    s._non_existent();                  // le serveur répond-il encore ?
                }
            } catch (Exception e) {
                if (subscribed != null) System.out.println("[web] serveur CORBA injoignable, nouvelle tentative...");
                subscribed = null;
                service = null;
            }
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
        }
    }

    /** Servant CORBA de la passerelle : le serveur l'appelle (oneway) à chaque changement. */
    static class Relay extends EventListenerPOA {
        @Override public void onEvent(String kind, String id, String label) {
            broadcast("data: " + Json.write(Json.obj("kind", kind, "id", id, "label", label)) + "\n\n");
        }
    }

    private static void broadcast(String msg) {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        for (OutputStream o : sse) {
            try { o.write(b); o.flush(); } catch (IOException e) { sse.remove(o); }
        }
    }

    // =================================================================== API
    private static void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"POST".equals(method)) { send(ex, 405, Json.obj("error", "Méthode non autorisée")); return; }
            // Protection CSRF : les requêtes qui modifient quelque chose doivent venir de notre JavaScript
            if ("POST".equals(method) && !"1".equals(ex.getRequestHeaders().getFirst("X-LF"))) {
                send(ex, 403, Json.obj("error", "Requête refusée")); return;
            }
            Map<String, String> p = params(ex);
            LostFoundService s = service;
            if (s == null) { send(ex, 503, Json.obj("error", "Le serveur CORBA ne répond pas. Vérifie qu'il est démarré.")); return; }
            String token = nz(cookie(ex, "lf"));      // CORBA refuse les chaînes null

            switch (path) {
                case "/api/register":
                    send(ex, 200, Json.obj("message", s.register(p.get("firstName"), p.get("lastName"), p.get("login"), p.get("contact"), p.get("password"))));
                    return;
                case "/api/login": {
                    Session session = s.login(p.get("login"), p.get("password"));
                    ex.getResponseHeaders().add("Set-Cookie", "lf=" + session.token + "; Path=/; HttpOnly; SameSite=Strict");
                    send(ex, 200, Json.obj("user", user(session.user)));
                    return;
                }
                case "/api/logout":
                    s.logout(token);
                    ex.getResponseHeaders().add("Set-Cookie", "lf=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
                    send(ex, 200, Json.obj("ok", true));
                    return;
                case "/api/me": send(ex, 200, Json.obj("user", user(s.me(token)))); return;
                case "/api/password": s.changePassword(token, p.get("old"), p.get("new")); send(ex, 200, Json.obj("ok", true)); return;
                case "/api/profile": s.updateProfile(token, p.get("contact")); send(ex, 200, Json.obj("user", user(s.me(token)))); return;
                case "/api/stats": {
                    Stats st = s.getStats(token);
                    send(ex, 200, Json.obj("lostOpen", st.lostOpen, "foundOpen", st.foundOpen, "resolved", st.resolved,
                            "members", st.members, "pendingUsers", st.pendingUsers, "pendingClaims", st.pendingClaims));
                    return;
                }
                case "/api/items": {
                    List<Object> out = new ArrayList<>();
                    for (Item i : s.listItems(token, p.get("q"), p.get("type"), p.get("cat"), p.get("status"))) out.add(item(i));
                    send(ex, 200, Json.obj("items", out));
                    return;
                }
                case "/api/item": send(ex, 200, Json.obj("item", item(s.getItem(token, p.get("id"))))); return;
                case "/api/photo": photo(ex, s.getPhoto(token, p.get("id"))); return;
                case "/api/items/create": send(ex, 200, Json.obj("item", item(s.createItem(token, draft(p), bytes(p.get("photo")))))); return;
                case "/api/items/update":
                    send(ex, 200, Json.obj("item", item(s.updateItem(token, p.get("id"), draft(p), bytes(p.get("photo")), "1".equals(p.get("removePhoto"))))));
                    return;
                case "/api/items/status": send(ex, 200, Json.obj("item", item(s.setItemStatus(token, p.get("id"), p.get("status"))))); return;
                case "/api/items/delete": s.deleteItem(token, p.get("id")); send(ex, 200, Json.obj("ok", true)); return;
                case "/api/claims/create": send(ex, 200, Json.obj("claim", claim(s.createClaim(token, p.get("itemId"), p.get("message"))))); return;
                case "/api/claims/mine": send(ex, 200, Json.obj("claims", claims(s.myClaims(token)))); return;
                case "/api/claims/received": send(ex, 200, Json.obj("claims", claims(s.receivedClaims(token)))); return;
                case "/api/claims/decide": send(ex, 200, Json.obj("claim", claim(s.decideClaim(token, p.get("id"), "1".equals(p.get("accept")))))); return;
                case "/api/admin/users": {
                    List<Object> out = new ArrayList<>();
                    for (UserInfo u : s.listUsers(token)) out.add(user(u));
                    send(ex, 200, Json.obj("users", out));
                    return;
                }
                case "/api/admin/status": s.setUserStatus(token, p.get("id"), p.get("status")); send(ex, 200, Json.obj("ok", true)); return;
                case "/api/admin/delete": s.deleteUser(token, p.get("id"), "1".equals(p.get("withItems"))); send(ex, 200, Json.obj("ok", true)); return;
                case "/api/events": events(ex, s, token); return;
                default: send(ex, 404, Json.obj("error", "Introuvable"));
            }
        } catch (ServiceError e) {                    // exception IDL déclarée par « raises »
            send(ex, e.auth ? 401 : 400, Json.obj("error", e.reason, "auth", e.auth));
        } catch (SystemException e) {                 // COMM_FAILURE, TRANSIENT… : serveur injoignable
            service = null;
            send(ex, 503, Json.obj("error", "Le serveur CORBA ne répond pas. Vérifie qu'il est démarré."));
        } catch (IllegalArgumentException e) {
            send(ex, 400, Json.obj("error", "Données invalides."));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Json.obj("error", "Erreur interne."));
        }
    }

    /** Flux d'événements temps réel vers le navigateur (Server-Sent Events). */
    private static void events(HttpExchange ex, LostFoundService s, String token) throws Exception {
        s.me(token);                                       // session valide obligatoire
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream o = ex.getResponseBody();
        o.write(": ok\n\n".getBytes(StandardCharsets.UTF_8));
        o.flush();
        sse.add(o);
        // Le fil reste ici tant que le navigateur est connecté
        while (sse.contains(o)) Thread.sleep(1000);
        try { o.close(); } catch (IOException ignored) { }
    }

    // ============================================================ conversions
    private static Map<String, Object> user(UserInfo u) {
        return Json.obj("id", u.id, "login", u.login, "firstName", u.firstName, "lastName", u.lastName,
                "name", u.firstName + " " + u.lastName, "contact", u.contact, "role", u.role, "status", u.status,
                "createdAt", u.createdAt, "items", u.items);
    }

    private static Map<String, Object> item(Item i) {
        return Json.obj("id", i.id, "type", i.type, "title", i.title, "description", i.description, "category", i.category,
                "place", i.place, "date", i.date, "contact", i.contact, "status", i.status, "ownerId", i.ownerId,
                "ownerName", i.ownerName, "createdAt", i.createdAt, "hasPhoto", i.hasPhoto, "photoRev", i.photoRev, "claims", i.claims);
    }

    private static Map<String, Object> claim(Claim c) {
        return Json.obj("id", c.id, "itemId", c.itemId, "itemTitle", c.itemTitle, "itemType", c.itemType,
                "claimantId", c.claimantId, "claimantName", c.claimantName, "claimantContact", c.claimantContact,
                "message", c.message, "status", c.status, "createdAt", c.createdAt);
    }

    private static List<Object> claims(Claim[] l) {
        List<Object> out = new ArrayList<>();
        for (Claim c : l) out.add(claim(c));
        return out;
    }

    private static ItemDraft draft(Map<String, String> p) {
        return new ItemDraft(nz(p.get("type")), nz(p.get("title")), nz(p.get("description")), nz(p.get("category")),
                nz(p.get("place")), nz(p.get("date")), nz(p.get("contact")));
    }

    private static byte[] bytes(String base64) {
        if (base64 == null || base64.isEmpty()) return new byte[0];      // séquence vide = pas de photo
        return Base64.getDecoder().decode(base64);
    }

    private static void photo(HttpExchange ex, byte[] b) throws IOException {
        if (b == null || b.length == 0) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
        String type = (b[0] & 0xFF) == 0xFF ? "image/jpeg" : b[0] == 'G' ? "image/gif" : b[0] == 'R' ? "image/webp" : "image/png";
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Cache-Control", "private, max-age=300");
        ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    // ============================================================== fichiers
    private static void files(HttpExchange ex) throws IOException {
        String path = URLDecoder.decode(ex.getRequestURI().getPath(), "UTF-8");
        if (path.equals("/")) path = "/index.html";
        Path f = WEB.resolve(path.substring(1)).normalize();
        if (!f.startsWith(WEB) || !Files.isRegularFile(f)) {          // empêche « ../../ » hors du dossier web
            byte[] b = "Introuvable".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
            return;
        }
        String name = f.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8" : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8" : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".png") ? "image/png" : "application/octet-stream";
        byte[] b = Files.readAllBytes(f);
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", type);
        h.add("X-Content-Type-Options", "nosniff");
        h.add("X-Frame-Options", "DENY");
        h.add("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'");
        h.add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    // ================================================================ outils
    private static String nz(String s) { return s == null ? "" : s; }

    private static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> p = new HashMap<String, String>() {          // valeur absente -> "" (pas de null en CORBA)
            @Override public String get(Object k) { String v = super.get(k); return v == null ? "" : v; }
        };
        parse(ex.getRequestURI().getRawQuery(), p);
        if ("POST".equals(ex.getRequestMethod())) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n, total = 0;
            try (InputStream in = ex.getRequestBody()) {
                while ((n = in.read(chunk)) > 0) {
                    total += n;
                    if (total > 6 * 1024 * 1024) throw new IllegalArgumentException("trop gros");
                    buf.write(chunk, 0, n);
                }
            }
            parse(new String(buf.toByteArray(), StandardCharsets.UTF_8), p);
        }
        return p;
    }

    private static void parse(String q, Map<String, String> p) throws UnsupportedEncodingException {
        if (q == null || q.isEmpty()) return;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            p.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> all = ex.getRequestHeaders().get("Cookie");
        if (all == null) return null;
        for (String line : all)
            for (String c : line.split(";")) {
                String t = c.trim();
                if (t.startsWith(name + "=")) return t.substring(name.length() + 1);
            }
        return null;
    }

    private static void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    static String lanIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);
            String ip = s.getLocalAddress().getHostAddress();
            if (!ip.startsWith("0.")) return ip;
        } catch (Exception ignored) { }
        return "adresse-de-ce-pc";
    }
}
