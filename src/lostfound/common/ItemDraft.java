package lostfound.common;

import java.io.Serializable;

/** Données saisies pour déclarer ou modifier un objet perdu / trouvé. */
public class ItemDraft implements Serializable {
    private static final long serialVersionUID = 1L;
    public String type;          // PERDU ou TROUVE
    public String title, description, category, place, date, contact;
}
