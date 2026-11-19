package lostfound.common;

import java.io.Serializable;

/** Vue publique d'un compte : ne contient JAMAIS le mot de passe ni son hachage. */
public class UserInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String id, login, firstName, lastName, contact, role, status;
    public final long createdAt;
    public final int items;

    public UserInfo(String id, String login, String firstName, String lastName, String contact,
                    String role, String status, long createdAt, int items) {
        this.id = id; this.login = login; this.firstName = firstName; this.lastName = lastName;
        this.contact = contact; this.role = role; this.status = status; this.createdAt = createdAt; this.items = items;
    }

    public String fullName() { return firstName + " " + lastName; }
    public boolean isAdmin() { return "ADMIN".equals(role); }
}
