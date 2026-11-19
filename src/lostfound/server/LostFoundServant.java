package lostfound.server;

import lostfound.common.ServiceException;
import lostfound.corba.*;
import org.omg.CORBA.SystemException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servant CORBA : l'objet qui implémente réellement l'interface IDL
 * LostFoundService. Il hérite du squelette LostFoundServicePOA généré par idlj.
 *
 * Son rôle est de faire la traduction entre le monde CORBA et la logique métier :
 *  - les structures IDL (lostfound.corba.Item…) <-> les objets du modèle (lostfound.common.Item…) ;
 *  - les listes Java -> séquences IDL (tableaux) ;
 *  - ServiceException -> exception IDL ServiceError ;
 *  - null -> chaîne vide (CORBA refuse les chaînes null).
 */
public class LostFoundServant extends LostFoundServicePOA {

    private final LostFoundLogic logic;
    /** Abonnés CORBA actuels, pour pouvoir les désabonner. */
    private final Map<EventListener, LostFoundLogic.Subscriber> subscribers = new ConcurrentHashMap<>();

    public LostFoundServant(LostFoundLogic logic) { this.logic = logic; }

    // ================================================== comptes / sessions
    @Override public String register(String firstName, String lastName, String userLogin, String contact, String password) throws ServiceError {
        try { return nz(logic.register(firstName, lastName, userLogin, contact, password)); } catch (ServiceException e) { throw err(e); }
    }

    @Override public Session login(String userLogin, String password) throws ServiceError {
        try {
            lostfound.common.Session s = logic.login(userLogin, password);
            return new Session(nz(s.token), user(s.user));
        } catch (ServiceException e) { throw err(e); }
    }

    @Override public void logout(String token) { logic.logout(token); }

    @Override public UserInfo me(String token) throws ServiceError {
        try { return user(logic.me(token)); } catch (ServiceException e) { throw err(e); }
    }

    @Override public void changePassword(String token, String oldPassword, String newPassword) throws ServiceError {
        try { logic.changePassword(token, oldPassword, newPassword); } catch (ServiceException e) { throw err(e); }
    }

    @Override public void updateProfile(String token, String contact) throws ServiceError {
        try { logic.updateProfile(token, contact); } catch (ServiceException e) { throw err(e); }
    }

    // ============================================================ annonces
    @Override public Item[] listItems(String token, String query, String type, String category, String status) throws ServiceError {
        try {
            List<lostfound.common.Item> l = logic.listItems(token, query, type, category, status);
            Item[] out = new Item[l.size()];
            for (int k = 0; k < out.length; k++) out[k] = item(l.get(k));
            return out;
        } catch (ServiceException e) { throw err(e); }
    }

    @Override public Item getItem(String token, String itemId) throws ServiceError {
        try { return item(logic.getItem(token, itemId)); } catch (ServiceException e) { throw err(e); }
    }

    @Override public byte[] getPhoto(String token, String itemId) throws ServiceError {
        try {
            byte[] b = logic.getPhoto(token, itemId);
            return b == null ? new byte[0] : b;                 // séquence vide = pas de photo
        } catch (ServiceException e) { throw err(e); }
    }

    @Override public Item createItem(String token, ItemDraft draft, byte[] photo) throws ServiceError {
        try { return item(logic.createItem(token, draft(draft), photo)); } catch (ServiceException e) { throw err(e); }
    }

    @Override public Item updateItem(String token, String itemId, ItemDraft draft, byte[] photo, boolean removePhoto) throws ServiceError {
        try { return item(logic.updateItem(token, itemId, draft(draft), photo, removePhoto)); } catch (ServiceException e) { throw err(e); }
    }

    @Override public Item setItemStatus(String token, String itemId, String status) throws ServiceError {
        try { return item(logic.setItemStatus(token, itemId, status)); } catch (ServiceException e) { throw err(e); }
    }

    @Override public void deleteItem(String token, String itemId) throws ServiceError {
        try { logic.deleteItem(token, itemId); } catch (ServiceException e) { throw err(e); }
    }

    // ========================================== demandes de récupération
    @Override public Claim createClaim(String token, String itemId, String message) throws ServiceError {
        try { return claim(logic.createClaim(token, itemId, message)); } catch (ServiceException e) { throw err(e); }
    }

    @Override public Claim[] myClaims(String token) throws ServiceError {
        try { return claims(logic.myClaims(token)); } catch (ServiceException e) { throw err(e); }
    }

    @Override public Claim[] receivedClaims(String token) throws ServiceError {
        try { return claims(logic.receivedClaims(token)); } catch (ServiceException e) { throw err(e); }
    }

    @Override public Claim decideClaim(String token, String claimId, boolean accept) throws ServiceError {
        try { return claim(logic.decideClaim(token, claimId, accept)); } catch (ServiceException e) { throw err(e); }
    }

    // ====================================================== administration
    @Override public UserInfo[] listUsers(String token) throws ServiceError {
        try {
            List<lostfound.common.UserInfo> l = logic.listUsers(token);
            UserInfo[] out = new UserInfo[l.size()];
            for (int k = 0; k < out.length; k++) out[k] = user(l.get(k));
            return out;
        } catch (ServiceException e) { throw err(e); }
    }

    @Override public void setUserStatus(String token, String userId, String status) throws ServiceError {
        try { logic.setUserStatus(token, userId, status); } catch (ServiceException e) { throw err(e); }
    }

    @Override public void deleteUser(String token, String userId, boolean withItems) throws ServiceError {
        try { logic.deleteUser(token, userId, withItems); } catch (ServiceException e) { throw err(e); }
    }

    @Override public Stats getStats(String token) throws ServiceError {
        try {
            lostfound.common.Stats s = logic.stats(token);
            return new Stats(s.lostOpen, s.foundOpen, s.resolved, s.members, s.pendingUsers, s.pendingClaims);
        } catch (ServiceException e) { throw err(e); }
    }

    // ================================================== temps réel (callbacks)
    /** Le client nous donne la RÉFÉRENCE de son objet EventListener : on l'appellera à chaque changement. */
    @Override public void subscribe(EventListener listener) {
        if (listener == null) return;
        LostFoundLogic.Subscriber s = (kind, id, label) -> {
            try { listener.onEvent(nz(kind), nz(id), nz(label)); }            // appel distant oneway
            catch (SystemException e) { subscribers.remove(listener); throw e; }  // client disparu
        };
        subscribers.put(listener, s);
        logic.subscribe(s);
    }

    @Override public void unsubscribe(EventListener listener) {
        for (EventListener l : subscribers.keySet())
            if (l._is_equivalent(listener)) logic.unsubscribe(subscribers.remove(l));
    }

    // ========================================================= conversions
    private static String nz(String s) { return s == null ? "" : s; }

    private static ServiceError err(ServiceException e) { return new ServiceError(nz(e.getMessage()), e.isAuth()); }

    private static UserInfo user(lostfound.common.UserInfo u) {
        return new UserInfo(nz(u.id), nz(u.login), nz(u.firstName), nz(u.lastName), nz(u.contact),
                nz(u.role), nz(u.status), u.createdAt, u.items);
    }

    private static Item item(lostfound.common.Item i) {
        return new Item(nz(i.id), nz(i.type), nz(i.title), nz(i.description), nz(i.category), nz(i.place), nz(i.date),
                nz(i.contact), nz(i.status), nz(i.ownerId), nz(i.ownerName), i.createdAt, i.hasPhoto, i.photoRev, i.claims);
    }

    private static Claim claim(lostfound.common.Claim c) {
        return new Claim(nz(c.id), nz(c.itemId), nz(c.itemTitle), nz(c.itemType), nz(c.claimantId), nz(c.claimantName),
                nz(c.claimantContact), nz(c.message), nz(c.status), c.createdAt);
    }

    private static Claim[] claims(List<lostfound.common.Claim> l) {
        Claim[] out = new Claim[l.size()];
        for (int k = 0; k < out.length; k++) out[k] = claim(l.get(k));
        return out;
    }

    private static lostfound.common.ItemDraft draft(ItemDraft d) {
        lostfound.common.ItemDraft x = new lostfound.common.ItemDraft();
        x.type = d.type; x.title = d.title; x.description = d.description; x.category = d.category;
        x.place = d.place; x.date = d.date; x.contact = d.contact;
        return x;
    }
}
