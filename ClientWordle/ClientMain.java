import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ClientMain {
    static String hostname;
    static int port;
    private enum msgType {LOGIN, REGISTER, LOGOUT, EXIT, PLAYWORDLE, SENDWORD, SENDSTATS, SHARE, OK, NOTOK}
    public static ConcurrentLinkedQueue<String> notifies;

    /*
     *   metodo utilizzato dal Thread per la ricezione delle notifiche UDP degli altri giocatori
     */

    public static class SharingReceiver implements Runnable{
        @Override
        public void run() {
            try {
                byte[] buffer = new byte[1024];
                notifies = new ConcurrentLinkedQueue<>();
                /* creazione del multicast socket */
                MulticastSocket socket = new MulticastSocket(4321);
                /* Inet address con indirizzo di multicast */
                InetAddress group = InetAddress.getByName("230.0.0.0");
                /* aggiunta del socket al gruppo di multicast */
                socket.joinGroup(group);
                while (true) {
                    /* preparazione del datagram packet per la ricezione del messaggio */
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), packet.getOffset(), packet.getLength());
                    /* se viene ricevuto il messaggio di terminazione termina */
                    if (msg.equals("STOP")) break;
                    /* aggiunta del messaggio ad ArrayList delle notifiche ricevute*/
                    notifies.add(msg);
                }
                socket.leaveGroup(group);
                socket.close();
            }
            catch (IOException e){
                e.printStackTrace();
            }
        }
    }

    /*
     * metodo utilizzato da main per leggere le proprietà del client
     */
    public static void readConfig() throws IOException {
        InputStream input = new FileInputStream("client.properties");
        Properties prop = new Properties();
        /* caricamento delle properties del client */
        prop.load(input);
        hostname = prop.getProperty("hostname");
        port = Integer.parseInt(prop.getProperty("port"));
        input.close();
    }
    public static void main(String[] args) throws IOException {
        String msgToServer;
        String username;
        String password;
        String guessedWord;
        String[] ack;
        readConfig();
        /*  creazione di uno stream socket TCP per connessione a hostname e porta port*/
        try(Socket socket = new Socket(hostname,port)){
            /* lettura bufferizzata dello stream dati dal socket per lettura efficiente */
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            /* scrittura formattata dello stream dati diretto al server */
            PrintWriter out = new PrintWriter(socket.getOutputStream(),true);
            Scanner keyboard = new Scanner(System.in);
            System.out.println("Welcome to WORDLE!");
            /* creazione del thread per la ricezione delle notifiche in background */
            Thread sharingReceiver = new Thread(new SharingReceiver());
            sharingReceiver.start();
            while(true){
                /* ciclo while per la lettura dei messaggi impartiti dall'utente */
                System.out.println("1) login\n2) register\n3) logout\n4) play wordle\n5) send word\n6) send me statistics\n7) share\n8) show me sharing\n9) exit");
                msgToServer = keyboard.nextLine();
                switch (msgToServer) {
                    case "1"://LOGIN
                        /* richiesta di login dell'utente*/
                        System.out.println("Inserisci username:");
                        username = keyboard.nextLine();
                        System.out.println("Inserisci password");
                        password = keyboard.nextLine();
                        out.println(msgType.LOGIN + " " + username + " " + password);
                        checkResult(in);
                        break;
                    case "2" : //REGISTER
                        /* richiesta di registrazione dell'utente */
                        System.out.println("Inserisci username:");
                        username = keyboard.nextLine();
                        System.out.println("Inserisci password");
                        password = keyboard.nextLine();
                        out.println(msgType.REGISTER + " " + username + " " + password);
                        checkResult(in);
                        break;
                    case "3": //LOGOUT
                        /* richiesta di logout dell'utente */
                        out.println(msgType.LOGOUT);
                        checkResult(in);
                        break;
                    case "4": //PLAYWORDLE
                        /* richiesta di partecipazione al gioco */
                        out.println(msgType.PLAYWORDLE);
                        checkResult(in);
                        break;
                    case "5": //INDOVINA GUESSED WORD
                        /* richiesta d'invio di una guessed word */
                        System.out.println("Inserisci la tua Guessed Word:");
                        guessedWord = keyboard.nextLine();
                        out.println(msgType.SENDWORD + " " + guessedWord);
                        checkResult(in);
                        break;
                    case "6": //SENDSTATS
                        /* richiesta di condivisione al client delle proprie statistiche */
                        out.println(msgType.SENDSTATS);
                        ack = in.readLine().split(" ", 2);
                        if (msgType.valueOf(ack[0]) == msgType.OK) {
                            /*  stampa a video le statistiche ricevute dal server   */
                            String[] splitStat = ack[1].split(" ");
                            System.out.println("Partite giocate: " + splitStat[0]);
                            System.out.println("Win rate: " + splitStat[1]);
                            System.out.println("Last streak: " + splitStat[2]);
                            System.out.println("Streak record: " + splitStat[3]);
                            for (int i = 1; i < 13; i++) {
                                System.out.println("Vittorie al " + i + " tentativo: " + splitStat[3 + i]);
                            }
                        } else if (msgType.valueOf(ack[0]) == msgType.NOTOK) {
                            System.out.println("error: " + ack[1]);
                        }
                        break;
                    case "7": //SHARE
                        /* richiesta di condivisione dei risultati raggiunti al server */
                        out.println(msgType.SHARE);
                        checkResult(in);
                        break;
                    case "8": //SHOW ME SHARING
                        String[] splitSuggestions;
                        /* lettura delle notifiche ricevute dal thread SharingReceiver*/
                        for (String e : notifies) {
                            splitSuggestions = e.split(" ");
                            System.out.println(splitSuggestions[0] + " ha condiviso i seguenti risultati:");
                            for (int i = 1; i < splitSuggestions.length; i++)
                                System.out.println("tentativo " + i + ": " + splitSuggestions[i]);
                        }
                        break;
                    case "9":
                        /*  invio di una richiesta di uscita dal gioco  */
                        out.println(msgType.EXIT);
                        ack = in.readLine().split(" ", 2);
                        if (msgType.valueOf(ack[0]) == msgType.OK) {
                            System.out.println(ack[1]);
                            return;
                        } else if (msgType.valueOf(ack[0]) == msgType.NOTOK) {
                            System.out.println("error: " + ack[1]);
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    /*
     *   metodo per controllare il messaggio ricevuto dal server
     */
    private static void checkResult(BufferedReader in) throws IOException {
        String[] ack;
        ack = in.readLine().split(" ", 2);
        /* controlla il primo campo del messaggio e controlla l'esito della richiesta*/
        if (msgType.valueOf(ack[0]) == msgType.OK) {
            System.out.println(ack[1]);
        } else if (msgType.valueOf(ack[0]) == msgType.NOTOK) {
            System.out.println("error: " + ack[1]);
        }
    }
}