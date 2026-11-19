package lostfound.server;

import lostfound.corba.LostFoundService;
import lostfound.corba.LostFoundServiceHelper;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NameComponent;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;

import java.nio.file.Paths;

/**
 * Démarre le serveur CORBA de LostFound :
 *  1. initialise l'ORB et active le POA racine (RootPOA) ;
 *  2. crée le servant LostFoundServant et obtient sa référence d'objet (IOR) ;
 *  3. publie cette référence dans le service de nommage (orbd) sous le nom « LostFound ».
 *
 * Lancement : java lostfound.server.ServerMain -ORBInitialPort 1050 -ORBInitialHost 127.0.0.1
 */
public class ServerMain {
    public static final String NAME = "LostFound";

    public static void main(String[] args) throws Exception {
        ORB orb = ORB.init(args, null);

        POA rootPOA = POAHelper.narrow(orb.resolve_initial_references("RootPOA"));
        rootPOA.the_POAManager().activate();

        Store store = Store.load(Paths.get("data"));
        LostFoundLogic logic = new LostFoundLogic(store);
        LostFoundServant servant = new LostFoundServant(logic);

        org.omg.CORBA.Object ref = rootPOA.servant_to_reference(servant);
        LostFoundService service = LostFoundServiceHelper.narrow(ref);

        NamingContextExt naming = NamingContextExtHelper.narrow(orb.resolve_initial_references("NameService"));
        NameComponent[] path = naming.to_name(NAME);
        naming.rebind(path, service);

        System.out.println("==============================================");
        System.out.println("  LostFound - serveur CORBA pret");
        System.out.println("  publie dans le service de nommage sous \"" + NAME + "\"");
        System.out.println("  " + store.accounts.size() + " comptes, " + store.items.size() + " objets (dossier data/)");
        System.out.println("==============================================");

        // Garde du service de nommage : orbd accepte qu'un autre programme remplace
        // le nom « LostFound ». Toutes les 5 s, on vérifie qu'il pointe toujours vers nous.
        Thread guard = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    org.omg.CORBA.Object current = naming.resolve(path);
                    if (!current._is_equivalent(service)) {
                        naming.rebind(path, service);
                        System.out.println("! le nom \"" + NAME + "\" avait ete detourne : retabli.");
                    }
                } catch (org.omg.CosNaming.NamingContextPackage.NotFound e) {
                    try { naming.rebind(path, service); } catch (Exception ignored) { }
                } catch (Exception ignored) { }
            }
        }, "garde-nommage");
        guard.setDaemon(true);
        guard.start();

        orb.run();                       // attend les requêtes des clients
    }
}
