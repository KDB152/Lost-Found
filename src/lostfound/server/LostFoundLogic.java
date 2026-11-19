package lostfound.server;

import lostfound.common.*;

import java.io.IOException;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/**
 * Logique métier de LostFound : comptes, annonces, demandes, administration.
 * Le servant CORBA (LostFoundServant) reçoit les appels distants et les confie
 * à cette classe.
 *
 * Règle d'or : toutes les vérifications (qui appelle, a-t-il le droit ?) sont
 * faites ICI. L'interface web ne fait que cacher des boutons ; un client
 * modifié qui appellerait directement deleteItem() serait quand même refusé.
 */
public class LostFoundLogic {

    /** Abonné aux notifications (le servant l'adapte vers un EventListener CORBA distant). */
    public interface Subscriber { void onEvent(String kind, String id, String label) throws Exception; }

    static final int MAX_PHOTO = 3 * 1024 * 1024;           // 3 Mo
    static final long SESSION_TTL = 12 * 3_600_000L;         // 12 h d'inactivité
    static final Set<String> CATEGORIES = new LinkedHashSet<>(Arrays.asList(
            "Documents", "Électronique", "Clés", "Sacs", "Vêtements", "Bijoux", "Lunettes", "Autre"));

    private final Store store;

    private static class Live { final String accountId; volatile long seen = System.currentTimeMillis(); Live(String a) { accountId = a; } }
    private final Map<String, Live> sessions = new ConcurrentHashMap<>();
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();       // login → {échecs, début, bloqué jusqu'à}
    private final List<Subscriber> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService notifier = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "notif"); t.setDaemon(true); return t; });

    public LostFoundLogic(Store store) {
        this.store = store;
    }

    // ================================================== comptes / sessions
    public synchronized String register(String first, String last, String login, String contact, String password)
            throws ServiceException {
        String f = Security.clean(first, 30), l = Security.clean(last, 30), c = Security.clean(contact, 80);
        String lg = Security.clean(login, 40).toLowerCase();
        if (f.isEmpty() || l.isEmpty()) throw new ServiceException("Prénom et nom obligatoires.");
        if (!lg.matches("[a-z0-9._@-]{3,40}"))
            throw new ServiceException("Identifiant : 3 à 40 caractères (lettres, chiffres, . _ - @).");
        if (password == null || password.length() < 6) throw new ServiceException("Mot de passe : 6 caractères minimum.");
        if (findByLogin(lg) != null) throw new ServiceException("Cet identifiant est déjà utilisé.");
        Store.Account a = Security.newAccount(lg, f, l, c, password, "USER");
        store.accounts.put(a.id, a);
        store.save();
        log("★ nouveau compte @" + a.login + " (" + a.fullName() + ") en attente d'approbation");
        fire("user", a.id, a.fullName());
        return "Compte créé ! Il sera actif dès qu'un administrateur l'aura approuvé.";
    }

    public Session login(String login, String password) throws ServiceException {
        String lg = Security.clean(login, 40).toLowerCase();
        long now = System.currentTimeMillis();
        long[] fl = failures.get(lg);
        if (fl != null && fl[2] > now)
            throw new ServiceException("Trop d'essais : réessaie dans " + ((fl[2] - now) / 1000 + 1) + " s.");
        Store.Account a = findByLogin(lg);
        if (a == null || !Security.check(a, password)) {
            recordFailure(lg, now);
            pause(400);
            throw new ServiceException("Identifiant ou mot de passe incorrect.");
        }
        failures.remove(lg);
        if ("EN_ATTENTE".equals(a.status)) throw new ServiceException("Ton compte attend l'approbation d'un administrateur.");
        if ("SUSPENDU".equals(a.status)) throw new ServiceException("Ton compte a été suspendu par un administrateur.");
        String token = Security.token();
        sessions.put(token, new Live(a.id));
        log("+ @" + a.login + " connecté(e)" + ("ADMIN".equals(a.role) ? " [ADMIN]" : ""));
        return new Session(token, info(a));
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    public UserInfo me(String token) throws ServiceException { return info(auth(token)); }

    public synchronized void changePassword(String token, String oldPassword, String newPassword) throws ServiceException {
        Store.Account a = auth(token);
        if (!Security.check(a, oldPassword)) throw new ServiceException("Ancien mot de passe incorrect.");
        if (newPassword == null || newPassword.length() < 6) throw new ServiceException("Nouveau mot de passe : 6 caractères minimum.");
        Security.setPassword(a, newPassword);
        store.save();
        // On ferme les AUTRES sessions de ce compte (ex. un PC oublié connecté)
        sessions.entrySet().removeIf(e -> e.getValue().accountId.equals(a.id) && !e.getKey().equals(token));
        log("✎ @" + a.login + " a changé son mot de passe");
    }

    public synchronized void updateProfile(String token, String contact) throws ServiceException {
        Store.Account a = auth(token);
        a.contact = Security.clean(contact, 80);
        store.save();
    }

    // ============================================================ objets
    public List<Item> listItems(String token, String query, String type, String category, String status) throws ServiceException {
        auth(token);
        String q = norm(query);
        List<Item> out = new ArrayList<>();
        synchronized (this) {
            for (Item i : store.items.values()) {
                if (type != null && !type.isEmpty() && !type.equals("TOUS") && !i.type.equals(type)) continue;
                if (category != null && !category.isEmpty() && !category.equals("TOUS") && !i.category.equals(category)) continue;
                if (status != null && !status.isEmpty() && !status.equals("TOUS") && !i.status.equals(status)) continue;
                if (!q.isEmpty() && !norm(i.title + " " + i.description + " " + i.place + " " + i.category + " " + i.ownerName).contains(q)) continue;
                out.add(view(i));
            }
        }
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    public Item getItem(String token, String itemId) throws ServiceException {
        auth(token);
        return view(item(itemId));
    }

    public byte[] getPhoto(String token, String itemId) throws ServiceException {
        auth(token);
        Item i = item(itemId);
        if (!i.hasPhoto) return null;
        try { return Files.readAllBytes(store.photo(i.id)); } catch (IOException e) { return null; }
    }

    public Item createItem(String token, ItemDraft d, byte[] photo) throws ServiceException {
        Store.Account a = auth(token);
        Item i = new Item();
        i.id = Security.id();
        apply(i, d);
        i.status = "OUVERT";
        i.ownerId = a.id;
        i.ownerName = a.fullName();
        i.createdAt = System.currentTimeMillis();
        if (i.contact.isEmpty()) i.contact = a.contact;
        synchronized (this) {
            if (photo != null && photo.length > 0) savePhoto(i, photo);
            store.items.put(i.id, i);
            store.save();
        }
        log("↑ @" + a.login + " a déclaré un objet " + ("PERDU".equals(i.type) ? "perdu" : "trouvé") + " : « " + i.title + " »");
        fire("item-created", i.id, (i.type.equals("PERDU") ? "Objet perdu : " : "Objet trouvé : ") + i.title);
        return view(i);
    }

    public Item updateItem(String token, String itemId, ItemDraft d, byte[] photo, boolean removePhoto) throws ServiceException {
        Store.Account a = auth(token);
        Item i;
        synchronized (this) {
            i = item(itemId);
            requireOwnerOrAdmin(a, i);
            apply(i, d);
            if (removePhoto) { i.hasPhoto = false; i.photoRev = 0; try { Files.deleteIfExists(store.photo(i.id)); } catch (IOException ignored) { } }
            if (photo != null && photo.length > 0) savePhoto(i, photo);
            store.save();
        }
        log("✎ @" + a.login + (isAdmin(a) && !i.ownerId.equals(a.id) ? " (admin)" : "") + " a modifié « " + i.title + " »");
        fire("item-updated", i.id, i.title);
        return view(i);
    }

    public Item setItemStatus(String token, String itemId, String status) throws ServiceException {
        Store.Account a = auth(token);
        if (!"OUVERT".equals(status) && !"RESOLU".equals(status)) throw new ServiceException("Statut inconnu.");
        Item i;
        synchronized (this) {
            i = item(itemId);
            requireOwner(a, i);                 // seul l'auteur sait si l'objet a été rendu
            i.status = status;
            store.save();
        }
        fire("item-updated", i.id, i.title);
        return view(i);
    }

    public void deleteItem(String token, String itemId) throws ServiceException {
        Store.Account a = auth(token);
        Item i;
        synchronized (this) {
            i = item(itemId);
            requireOwnerOrAdmin(a, i);
            removeItem(i);
            store.save();
        }
        log("✕ @" + a.login + (isAdmin(a) && !i.ownerId.equals(a.id) ? " (admin)" : "") + " a supprimé « " + i.title + " »");
        fire("item-deleted", i.id, i.title);
    }

    private void removeItem(Item i) {
        store.items.remove(i.id);
        store.claims.values().removeIf(c -> c.itemId.equals(i.id));
        try { Files.deleteIfExists(store.photo(i.id)); } catch (IOException ignored) { }
    }

    // ============================================== demandes de récupération
    public Claim createClaim(String token, String itemId, String message) throws ServiceException {
        Store.Account a = auth(token);
        Claim c;
        synchronized (this) {
            Item i = item(itemId);
            if (i.ownerId.equals(a.id)) throw new ServiceException("C'est ta propre déclaration.");
            if (!"OUVERT".equals(i.status)) throw new ServiceException("Cet objet est déjà marqué comme résolu.");
            for (Claim x : store.claims.values())
                if (x.itemId.equals(i.id) && x.claimantId.equals(a.id) && "EN_ATTENTE".equals(x.status))
                    throw new ServiceException("Tu as déjà une demande en attente pour cet objet.");
            String msg = Security.cleanLong(message, 500);
            if (msg.length() < 5) throw new ServiceException("Explique en quelques mots (au moins 5 caractères).");
            c = new Claim();
            c.id = Security.id();
            c.itemId = i.id; c.itemTitle = i.title; c.itemType = i.type;
            c.claimantId = a.id; c.claimantName = a.fullName(); c.claimantContact = a.contact;
            c.message = msg; c.status = "EN_ATTENTE"; c.createdAt = System.currentTimeMillis();
            store.claims.put(c.id, c);
            store.save();
        }
        log("? @" + a.login + " a fait une demande pour « " + c.itemTitle + " »");
        fire("claim", c.itemId, c.itemTitle);
        return c.copy();
    }

    public List<Claim> myClaims(String token) throws ServiceException {
        Store.Account a = auth(token);
        List<Claim> out = new ArrayList<>();
        synchronized (this) { for (Claim c : store.claims.values()) if (c.claimantId.equals(a.id)) out.add(c.copy()); }
        out.sort((x, y) -> Long.compare(y.createdAt, x.createdAt));
        return out;
    }

    public List<Claim> receivedClaims(String token) throws ServiceException {
        Store.Account a = auth(token);
        List<Claim> out = new ArrayList<>();
        synchronized (this) {
            for (Claim c : store.claims.values()) {
                Item i = store.items.get(c.itemId);
                if (i != null && i.ownerId.equals(a.id)) out.add(c.copy());
            }
        }
        out.sort((x, y) -> Long.compare(y.createdAt, x.createdAt));
        return out;
    }

    public Claim decideClaim(String token, String claimId, boolean accept) throws ServiceException {
        Store.Account a = auth(token);
        Claim c;
        synchronized (this) {
            c = store.claims.get(claimId);
            if (c == null) throw new ServiceException("Demande introuvable.");
            Item i = item(c.itemId);
            requireOwner(a, i);                 // c'est l'auteur de l'annonce qui rend l'objet
            if (!"EN_ATTENTE".equals(c.status)) throw new ServiceException("Cette demande a déjà été traitée.");
            c.status = accept ? "ACCEPTEE" : "REFUSEE";
            if (accept) {                     // objet rendu : résolu, les autres demandes sont refusées
                i.status = "RESOLU";
                for (Claim x : store.claims.values())
                    if (x.itemId.equals(i.id) && x != c && "EN_ATTENTE".equals(x.status)) x.status = "REFUSEE";
            }
            store.save();
        }
        log((accept ? "✔ " : "✕ ") + "@" + a.login + (accept ? " a accepté" : " a refusé") + " la demande de " + c.claimantName + " (« " + c.itemTitle + " »)");
        fire("claim-decided", c.itemId, c.itemTitle);
        return c.copy();
    }

    // ===================================================== administration
    public List<UserInfo> listUsers(String token) throws ServiceException {
        requireAdmin(token);
        List<UserInfo> out = new ArrayList<>();
        synchronized (this) { for (Store.Account a : store.accounts.values()) out.add(info(a)); }
        out.sort((x, y) -> {
            int p = Boolean.compare(!"EN_ATTENTE".equals(x.status), !"EN_ATTENTE".equals(y.status));   // en attente d'abord
            return p != 0 ? p : x.lastName.compareToIgnoreCase(y.lastName);
        });
        return out;
    }

    public void setUserStatus(String token, String userId, String status) throws ServiceException {
        Store.Account admin = requireAdmin(token);
        if (!Arrays.asList("ACTIF", "SUSPENDU").contains(status)) throw new ServiceException("Statut inconnu.");
        final Store.Account t = account(userId);
        synchronized (this) {
            if (t.id.equals(admin.id)) throw new ServiceException("Tu ne peux pas modifier ton propre statut.");
            boolean approval = "EN_ATTENTE".equals(t.status) && "ACTIF".equals(status);
            t.status = status;
            store.save();
            if ("SUSPENDU".equals(status)) sessions.values().removeIf(s -> s.accountId.equals(t.id));    // déconnexion immédiate
            log("★ admin @" + admin.login + (approval ? " a approuvé" : "SUSPENDU".equals(status) ? " a suspendu" : " a réactivé") + " @" + t.login);
        }
        fire("user", t.id, t.fullName());
    }

    public void deleteUser(String token, String userId, boolean withItems) throws ServiceException {
        Store.Account admin = requireAdmin(token);
        final Store.Account t = account(userId);
        synchronized (this) {
            if (t.id.equals(admin.id)) throw new ServiceException("Tu ne peux pas supprimer ton propre compte.");
            store.accounts.remove(t.id);
            sessions.values().removeIf(s -> s.accountId.equals(t.id));
            store.claims.values().removeIf(c -> c.claimantId.equals(t.id) && "EN_ATTENTE".equals(c.status));
            if (withItems) for (Item i : new ArrayList<>(store.items.values())) if (i.ownerId.equals(t.id)) removeItem(i);
            store.save();
        }
        log("✕ admin @" + admin.login + " a supprimé le compte @" + t.login + (withItems ? " et ses publications" : ""));
        fire("user", t.id, t.fullName());
        if (withItems) fire("item-deleted", "", "");
    }

    public Stats stats(String token) throws ServiceException {
        Store.Account a = auth(token);
        Stats s = new Stats();
        synchronized (this) {
            for (Item i : store.items.values()) {
                if ("RESOLU".equals(i.status)) s.resolved++;
                else if ("PERDU".equals(i.type)) s.lostOpen++;
                else s.foundOpen++;
            }
            for (Store.Account x : store.accounts.values()) {
                if ("ACTIF".equals(x.status)) s.members++;
                if ("EN_ATTENTE".equals(x.status)) s.pendingUsers++;
            }
            for (Claim c : store.claims.values()) {
                Item i = store.items.get(c.itemId);
                if ("EN_ATTENTE".equals(c.status) && i != null && i.ownerId.equals(a.id)) s.pendingClaims++;
            }
        }
        if (!isAdmin(a)) s.pendingUsers = 0;
        return s;
    }

    // ============================================================ temps réel
    public void subscribe(Subscriber l) { listeners.add(l); log("~ un client s'est abonné aux notifications (" + listeners.size() + ")"); }
    public void unsubscribe(Subscriber l) { listeners.remove(l); }

    /** Callback CORBA vers chaque abonné ; un abonné injoignable est retiré. */
    private void fire(String kind, String id, String label) {
        for (Subscriber l : listeners) {
            notifier.submit(() -> {
                try { l.onEvent(kind, id, label); }
                catch (Exception e) { listeners.remove(l); log("~ abonné injoignable retiré"); }
            });
        }
    }

    // ============================================================= outils
    private Store.Account auth(String token) throws ServiceException {
        Live s = token == null ? null : sessions.get(token);
        long now = System.currentTimeMillis();
        if (s == null || now - s.seen > SESSION_TTL) {
            if (s != null) sessions.remove(token);
            throw new ServiceException("Session expirée : reconnecte-toi.", true);
        }
        Store.Account a = store.accounts.get(s.accountId);
        if (a == null || !"ACTIF".equals(a.status)) { sessions.remove(token); throw new ServiceException("Compte indisponible.", true); }
        s.seen = now;
        return a;
    }

    private Store.Account requireAdmin(String token) throws ServiceException {
        Store.Account a = auth(token);
        if (!isAdmin(a)) throw new ServiceException("Réservé à l'administrateur.");
        return a;
    }

    private static boolean isAdmin(Store.Account a) { return "ADMIN".equals(a.role); }

    private static void requireOwner(Store.Account a, Item i) throws ServiceException {
        if (!i.ownerId.equals(a.id))
            throw new ServiceException("Seul l'auteur de l'annonce peut faire cela.");
    }

    private void requireOwnerOrAdmin(Store.Account a, Item i) throws ServiceException {
        if (!i.ownerId.equals(a.id) && !isAdmin(a))
            throw new ServiceException("Seuls l'auteur de la déclaration et l'administrateur peuvent faire cela.");
    }

    private Store.Account findByLogin(String login) {
        for (Store.Account a : store.accounts.values()) if (a.login.equals(login)) return a;
        return null;
    }

    private Store.Account account(String id) throws ServiceException {
        Store.Account a = id == null ? null : store.accounts.get(id);
        if (a == null) throw new ServiceException("Compte introuvable.");
        return a;
    }

    private Item item(String id) throws ServiceException {
        Item i = id == null ? null : store.items.get(id);
        if (i == null) throw new ServiceException("Objet introuvable (peut-être supprimé).");
        return i;
    }

    private void apply(Item i, ItemDraft d) throws ServiceException {
        if (d == null) throw new ServiceException("Formulaire vide.");
        String type = d.type == null ? "" : d.type;
        if (!"PERDU".equals(type) && !"TROUVE".equals(type)) throw new ServiceException("Choisis « perdu » ou « trouvé ».");
        String title = Security.clean(d.title, 80);
        if (title.length() < 3) throw new ServiceException("Titre : 3 caractères minimum.");
        String cat = Security.clean(d.category, 30);
        if (!CATEGORIES.contains(cat)) cat = "Autre";
        String date = Security.clean(d.date, 10);
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new ServiceException("Date invalide.");
        i.type = type; i.title = title; i.category = cat; i.date = date;
        i.description = Security.cleanLong(d.description, 1000);
        i.place = Security.clean(d.place, 80);
        i.contact = Security.clean(d.contact, 80);
        if (i.place.isEmpty()) throw new ServiceException("Indique le lieu.");
    }

    private void savePhoto(Item i, byte[] photo) throws ServiceException {
        if (photo.length > MAX_PHOTO) throw new ServiceException("Photo trop lourde (3 Mo maximum).");
        if (!Security.isImage(photo)) throw new ServiceException("Le fichier n'est pas une image (JPEG, PNG, GIF ou WebP).");
        try {
            Files.write(store.photo(i.id), photo);
            i.hasPhoto = true;
            i.photoRev = System.currentTimeMillis();
        } catch (IOException e) {
            throw new ServiceException("Enregistrement de la photo impossible.");
        }
    }

    /** Copie envoyée au client, avec le nombre de demandes en attente. */
    private Item view(Item i) {
        Item v = i.copy();
        int n = 0;
        for (Claim c : store.claims.values()) if (c.itemId.equals(i.id) && "EN_ATTENTE".equals(c.status)) n++;
        v.claims = n;
        return v;
    }

    private UserInfo info(Store.Account a) {
        int n = 0;
        for (Item i : store.items.values()) if (i.ownerId.equals(a.id)) n++;
        return new UserInfo(a.id, a.login, a.firstName, a.lastName, a.contact, a.role, a.status, a.createdAt, n);
    }

    private void recordFailure(String login, long now) {
        long[] f = failures.computeIfAbsent(login, k -> new long[]{0, now, 0});
        synchronized (f) {
            if (now - f[1] > 5 * 60_000) { f[0] = 0; f[1] = now; }
            if (++f[0] >= 5) { f[2] = now + 60_000; f[0] = 0; log("! 5 échecs de connexion pour @" + login + " : blocage 60 s"); }
        }
    }

    static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().trim();
    }

    private static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static void log(String s) { System.out.printf("[%tT] %s%n", new Date(), s); }
}
