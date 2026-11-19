package lostfound.server;

import lostfound.common.Claim;
import lostfound.common.Item;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Données du serveur, sauvegardées dans data/lostfound.dat (sérialisation Java)
 * et data/photos/ (une image par objet). Créées avec des exemples au premier lancement.
 */
public class Store implements Serializable {
    private static final long serialVersionUID = 2L;

    /** Compte interne : contrairement à UserInfo, il contient le hachage du mot de passe. */
    static class Account implements Serializable {
        private static final long serialVersionUID = 1L;
        String id, login, firstName, lastName, contact, role, status;
        byte[] salt, hash;
        long createdAt;
        String fullName() { return firstName + " " + lastName; }
    }

    final Map<String, Account> accounts = new LinkedHashMap<>();   // clé : id
    final Map<String, Item> items = new LinkedHashMap<>();
    final Map<String, Claim> claims = new LinkedHashMap<>();

    transient Path dir;

    static Store load(Path dir) {
        Path file = dir.resolve("lostfound.dat");
        copySeed(dir, file);
        Store s = null;
        if (Files.exists(file)) {
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
                s = (Store) in.readObject();
            } catch (Exception e) {
                System.out.println("! Données illisibles (" + e + "), nouveau départ avec les exemples.");
            }
        }
        boolean fresh = s == null;
        if (fresh) s = new Store();
        s.dir = dir;
        try { Files.createDirectories(dir.resolve("photos")); } catch (IOException ignored) { }
        if (fresh) s.seed();
        return s;
    }

    /** Premier lancement : copie les données de départ (dossier seed/, avec les photos) dans data/. */
    private static void copySeed(Path dir, Path file) {
        Path seed = dir.toAbsolutePath().getParent().resolve("seed");
        if (Files.exists(file) || !Files.exists(seed.resolve("lostfound.dat"))) return;
        try {
            Files.createDirectories(dir.resolve("photos"));
            Files.copy(seed.resolve("lostfound.dat"), file);
            if (Files.isDirectory(seed.resolve("photos")))
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(seed.resolve("photos"))) {
                    for (Path ph : ds) Files.copy(ph, dir.resolve("photos").resolve(ph.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            System.out.println("Donnees de depart copiees depuis seed/");
        } catch (IOException e) {
            System.out.println("! Copie des donnees de depart impossible : " + e);
        }
    }

    synchronized void save() {
        Path file = dir.resolve("lostfound.dat"), tmp = dir.resolve("lostfound.tmp");
        try {
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) { out.writeObject(this); }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);       // écriture « atomique »
        } catch (IOException e) {
            System.out.println("! Sauvegarde impossible : " + e);
        }
    }

    Path photo(String itemId) { return dir.resolve("photos").resolve(itemId + ".img"); }

    // ------------------------------------------------------------ exemples
    private void seed() {
        // login, prénom, nom, mot de passe — tous les comptes sont actifs
        String[][] people = {
            { "mehdi",   "Mehdi",       "El Abed",      "mehdi123"   },   // le seul administrateur
            { "farah",   "Farah",       "Ncir",         "farah123"   },
            { "medali",  "Mohamed Ali", "Lahmar",       "medali123"  },
            { "maha",    "Maha",        "Khemaja",      "maha123"    },
            { "mohamed", "Mohamed",     "Ben Hmida",    "mohamed123" },
            { "idriss",  "Idriss",      "Mathlouthi",   "idriss123"  },
            { "chawki",  "Chawki",      "Nassraoui",    "chawki123"  },
            { "helmi",   "Helmi",       "Boulabi",      "helmi123"   },
            { "saif",    "Saif",        "Naija",        "saif123"    },
            { "maram",   "Maram",       "Jegham",       "maram123"   },
            { "ayoub",   "Ayoub",       "Gharsellaoui", "ayoub123"   },
        };
        Map<String, Account> by = new HashMap<>();
        for (String[] u : people) {
            Account a = Security.newAccount(u[0], u[1], u[2], u[0] + "@univ.tn", u[3], "mehdi".equals(u[0]) ? "ADMIN" : "USER");
            a.status = "ACTIF";
            accounts.put(a.id, a);
            by.put(u[0], a);
        }

        long now = System.currentTimeMillis(), day = 86_400_000L;
        add(item(by.get("farah"), "PERDU", "Dossier d'architecture", "Classeur bleu avec les cours et notes d'architecture de Mme Khemaja.",
                "Documents", "Amphithéâtre A", now - day), now - day);
        add(item(by.get("maha"), "TROUVE", "Carte d'étudiant", "Carte universitaire trouvée près du bloc M, au nom de S. B.",
                "Documents", "Bloc M12", now - 2 * day), now - 2 * day);
        add(item(by.get("helmi"), "TROUVE", "Clé USB bleue", "Clé USB 32 Go avec un porte-clés en forme d'étoile.",
                "Électronique", "Bibliothèque", now - 3 * day), now - 3 * day);
        add(item(by.get("saif"), "PERDU", "Écouteurs sans fil blancs", "Boîtier blanc, perdu entre la cafétéria et le parking.",
                "Électronique", "Cafétéria", now - 5 * 3_600_000L), now - 5 * 3_600_000L);
        add(item(by.get("maram"), "TROUVE", "Trousseau de 3 clés", "Trois clés avec un badge vert, trouvées sur un banc.",
                "Clés", "Jardin central", now - 4 * day), now - 4 * day);
        save();
    }

    private Item item(Account owner, String type, String title, String desc, String cat, String place, long when) {
        Item i = new Item();
        i.id = Security.id();
        i.type = type; i.title = title; i.description = desc; i.category = cat; i.place = place;
        i.date = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(when));
        i.contact = owner.contact; i.status = "OUVERT"; i.ownerId = owner.id; i.ownerName = owner.fullName();
        return i;
    }

    private void add(Item i, long createdAt) { i.createdAt = createdAt; items.put(i.id, i); }
}
