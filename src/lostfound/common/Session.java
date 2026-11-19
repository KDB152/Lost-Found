package lostfound.common;

import java.io.Serializable;

/** Résultat d'une connexion : un jeton secret + le compte connecté. */
public class Session implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String token;
    public final UserInfo user;

    public Session(String token, UserInfo user) { this.token = token; this.user = user; }
}
