package lostfound.server;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Outils de sécurité : hachage des mots de passe, jetons, identifiants, nettoyage. */
final class Security {
    private Security() {}

    static final SecureRandom RANDOM = new SecureRandom();

    static String id() {
        byte[] b = new byte[9];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String token() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** PBKDF2-SHA256 avec sel aléatoire : le mot de passe n'est jamais stocké. */
    static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 60_000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("hachage impossible", e);
        }
    }

    static boolean check(Store.Account a, String password) {
        return password != null && MessageDigest.isEqual(a.hash, hash(password, a.salt));
    }

    static void setPassword(Store.Account a, String password) {
        a.salt = new byte[16];
        RANDOM.nextBytes(a.salt);
        a.hash = hash(password, a.salt);
    }

    static Store.Account newAccount(String login, String first, String last, String contact, String password, String role) {
        Store.Account a = new Store.Account();
        a.id = id();
        a.login = login.toLowerCase();
        a.firstName = first; a.lastName = last; a.contact = contact;
        a.role = role; a.status = "EN_ATTENTE";
        a.createdAt = System.currentTimeMillis();
        setPassword(a, password);
        return a;
    }

    /** Texte sûr : sans caractères de contrôle, espaces normalisés, longueur bornée. */
    static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Texte long (description) : on garde les retours à la ligne. */
    static String cleanLong(String s, int max) {
        if (s == null) return "";
        s = s.replace("\r", "").replaceAll("[\\x00-\\x09\\x0B-\\x1F\\x7F]", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Vérifie que les octets sont bien une image (JPEG, PNG, GIF ou WebP) et non un fichier piégé. */
    static boolean isImage(byte[] b) {
        if (b == null || b.length < 12) return false;
        boolean jpeg = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
        boolean png = (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean gif = b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
        boolean webp = b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        return jpeg || png || gif || webp;
    }
}
