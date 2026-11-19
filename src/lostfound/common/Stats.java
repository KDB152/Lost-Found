package lostfound.common;

import java.io.Serializable;

/** Chiffres du tableau de bord. */
public class Stats implements Serializable {
    private static final long serialVersionUID = 1L;
    public int lostOpen, foundOpen, resolved, members, pendingUsers, pendingClaims;
}
