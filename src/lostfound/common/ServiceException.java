package lostfound.common;

/**
 * Erreur métier renvoyée par le serveur (identifiants incorrects, action
 * interdite…). Le servant CORBA la traduit en exception IDL ServiceError ;
 * « auth » indique que la session n'est pas (ou plus) valide.
 */
public class ServiceException extends Exception {
    private static final long serialVersionUID = 1L;
    private final boolean auth;

    public ServiceException(String message) { this(message, false); }
    public ServiceException(String message, boolean auth) { super(message); this.auth = auth; }

    public boolean isAuth() { return auth; }
}
