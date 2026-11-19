package lostfound.common;

import java.io.Serializable;

/** Une déclaration d'objet perdu ou trouvé. */
public class Item implements Serializable {
    private static final long serialVersionUID = 1L;
    public String id, type, title, description, category, place, date, contact;
    public String status;        // OUVERT ou RESOLU
    public String ownerId, ownerName;
    public long createdAt;
    public boolean hasPhoto;
    public long photoRev;        // change à chaque nouvelle photo : force le navigateur à la recharger
    public int claims;           // nombre de demandes en attente

    public Item copy() {
        Item i = new Item();
        i.id = id; i.type = type; i.title = title; i.description = description; i.category = category;
        i.place = place; i.date = date; i.contact = contact; i.status = status; i.ownerId = ownerId;
        i.ownerName = ownerName; i.createdAt = createdAt; i.hasPhoto = hasPhoto; i.photoRev = photoRev; i.claims = claims;
        return i;
    }
}
