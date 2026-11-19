package lostfound.common;

import java.io.Serializable;

/** Demande de récupération : « c'est à moi » ou « je l'ai trouvé ». */
public class Claim implements Serializable {
    private static final long serialVersionUID = 1L;
    public String id, itemId, itemTitle, itemType, claimantId, claimantName, claimantContact, message;
    public String status;        // EN_ATTENTE, ACCEPTEE, REFUSEE
    public long createdAt;

    public Claim copy() {
        Claim c = new Claim();
        c.id = id; c.itemId = itemId; c.itemTitle = itemTitle; c.itemType = itemType; c.claimantId = claimantId;
        c.claimantName = claimantName; c.claimantContact = claimantContact; c.message = message;
        c.status = status; c.createdAt = createdAt;
        return c;
    }
}
