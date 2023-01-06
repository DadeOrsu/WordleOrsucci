import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
public class ServerMain {
    /*  tipi di messaggi che è possibile scambiarsi tra client e server */
    private enum msgType {LOGIN, REGISTER, LOGOUT, EXIT, PLAYWORDLE, SENDWORD, SENDSTATS, SHARE, OK, NOTOK}
    /*  tipi di stato che può assumere un utente    */
    private enum userState {LOGGED, INTERRUPTED, INGAME}
    /*  numero di porta su cui si mette in ascolto il server    */
    private static int port;
    /*  porta UDP utilizzata per condividere i risultati degli utenti   */
    private static int udpPort;
    /*  indirizzo del gruppo di broadcast   */
    private static String udpAddress;
    /*  nome del file contenente i dati degli utenti */
    private static String usersFile;
    /*  parola segreta estratta dal vocabolario in words.txt    */
    private static volatile String secretWord;
    private static volatile ConcurrentHashMap<String,User> users;
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    /*  tipo utilizzato dal metodo getUsers() per de-serializzare gli utenti in una ConcurrentHashMap*/
    private static final Type hm_type = new TypeToken<ConcurrentHashMap<String, User>>(){}.getType();

    /*
     *    funzione utilizzata all'apertura del server per de-serializzare in una ConcurrentHashmap i dati degli utenti
     */

    private static void getUsers() throws IOException {
        JsonReader reader = new JsonReader(new FileReader("users.json"));
        users = gson.fromJson(reader, hm_type);
        if (users == null) {
            users = new ConcurrentHashMap<>();
        }
        reader.close();
    }

    /*
     *    Runnable utilizzato da un thread del server per avviare una nuova partita
     *    -resetta i tentativi
     *    -estrae una nuova secret word
     *    -resetta lo stato di vittoria giornaliera
     *    -resetta i suggerimenti
     */

    public static class WordExtractor implements Runnable {
        private static String extractSecretWord() throws IOException {
            RandomAccessFile words = new RandomAccessFile("words.txt", "r");
            long randomLocation = (long) (Math.random() * words.length());
            randomLocation = randomLocation - randomLocation % 11;
            words.seek(randomLocation);
            String secretWord = words.readLine();
            words.close();
            return secretWord;
        }
        @Override
        public void run() {
            try {
                secretWord = extractSecretWord();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for(String e: users.keySet()){

                users.get(e).remainingTrials = 12;
                users.get(e).hasWonToday = false;
                users.get(e).wordSuggestions = new ArrayList<>();
            }
            try {
                updateUsers();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("La nuova parola estratta e': " + secretWord);
        }
    }


    /*
     *   ricerca binaria della parola all'interno del vocabolario
     */
    public static Boolean checkVocabulary(String word) throws IOException {
        try (final RandomAccessFile words = new RandomAccessFile("words.txt", "r")) {
            long start = 0;
            long end = words.length();
            String wordRidden;
            while (start <= end) {
                long mid = ((start + end) / 2);
                mid = mid - mid % 11;
                words.seek(mid);
                wordRidden = words.readLine();
                //System.out.println(wordRidden);
                if (wordRidden.compareTo(word) == 0)
                    return true;
                else if (wordRidden.compareTo(word) < 0)
                    start = mid + 10;
                else
                    end = mid - 10;
            }
            return false;
        }
    }

    /*
     *   metodo utilizzato per l'invio dei messaggi UDP ai client che fanno parte del gruppo di Broadcast
     */
    private static synchronized void sendUDPMessage(String message, String ipAddress, int port) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        InetAddress group = InetAddress.getByName(ipAddress);
        byte[] msg = message.getBytes();
        DatagramPacket packet = new DatagramPacket(msg, msg.length, group, port);
        socket.send(packet);
        socket.close();
    }

    /*
     *      metodo utilizzato per aggiornare i dati degli utenti presenti del file JSON per renderlo sempre consistente
     */
    private static synchronized void updateUsers() throws IOException {
        /*  aggiornamento dei dati utente nel file JSON mediante Gson */
        JsonWriter jsonWriter = new JsonWriter(new FileWriter(usersFile, false));
        jsonWriter.setIndent("  ");
        gson.toJson(users, hm_type, jsonWriter);
        jsonWriter.flush();
        jsonWriter.close();
    }

    /*
     *   metodo utilizzato per vedere eseguire il login
     */
    private static int login(String username, String password){
        if(users.containsKey(username)){ //username exists
            if(users.get(username).password.equals(password)) {
                /* se la password è corretta  */
                return 1;
            }
            else /* nel caso di password errata */
                return -1;
        }
        else    /*  nel caso in cui l'username non sia registrato   */
            return 0;
    }

    /*
     *   metodo utilizzato per eseguire la registrazione degli utenti
     */
    private static int register(String username, String password) throws IOException {
        /*  se l'utente non è già registrato non è presente nel file JSON (e quindi nella ConcurrentHashMap)    */
        if(!users.containsKey(username)){
            users.put(username, new User(username, password));
            updateUsers();
            return 1;
        }
        else    /*  nel caso in cui l'username esista già   */
            return 0;
    }

    /*
     *   Struttura dati contenente i dati di uno User all'interno della HashMap e del file JSON
     */
    private static class User {
        public String username;
        public String password;
        public int matchPlayed;
        public int matchWon;
        public int lastStreak;
        public int streakRecord;
        public boolean lastMatchWon;
        public int remainingTrials;
        public HashMap<Integer, Integer> guessDistribution;
        public boolean hasWonToday;
        public ArrayList<String> wordSuggestions;

        public User(String username, String password) {
            this.username = username;
            this.password = password;
            this.matchPlayed = 0;
            this.matchWon = 0;
            this.lastStreak = 0;
            this.streakRecord = 0;
            this.lastMatchWon = false;
            this.remainingTrials = 12;
            this.hasWonToday = false;
            this.guessDistribution = new HashMap<>();
            this.wordSuggestions = new ArrayList<>();
            for (int i=1;i<13;i++){
                this.guessDistribution.put(i,0);
            }
        }
    }

    /*
     *   Classe Runnable utilizzata per gestire ciascun client connesso al server
     */
    public static class ClientHandler implements Runnable {
        private userState clientUserState;
        /*  lettura bufferizzata dei dati provenienti dal client    */
        private final BufferedReader in;
        /*  scrittura formattata dei dati sullo stream di uscita    */
        private final PrintWriter out;
        /*  player mantiene il riferimento allo User che sta attualmente giocando la partita (prima del login è null)   */
        private User player;
        Socket socket;
        public ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.player = null;
            this.in = new BufferedReader(new InputStreamReader(this.socket.getInputStream()));
            this.out = new PrintWriter(this.socket.getOutputStream(), true);
        }
        @Override
        public void run() {
            String msg;
            String[] msgToken;
            String guessedWord;
            String [] guessedSplit;
            String [] secretWordSplit;
            StringBuilder stringCompared;
            try {
                while (this.clientUserState!=userState.INTERRUPTED) {
                    msg = this.in.readLine();
                    msgToken = msg.split(" ");
                    //msgToken[0] --> operazione
                    //msgToken [n] --> parametri
                    /*  controllo di msgType per vedere il tipo di richiesta effettuata dall'utente */
                    switch (msgType.valueOf(msgToken[0])) {
                        case LOGIN:
                            /*  se player!=null allora è già stato eseguito il login    */
                            if (player != null)
                                this.out.println(msgType.NOTOK + " Hai già eseguito il login");
                            else {
                                switch (login(msgToken[1], msgToken[2])) {
                                    case 0:
                                        this.out.println(msgType.NOTOK + " questo username non esiste!");
                                        break;
                                    case 1:
                                        this.out.println(msgType.OK +" login eseguito con successo, benvenuto!");
                                        /*  se il login è stato eseguito con successo l'utente passa allo stato di LOGGED   */
                                        this.clientUserState = userState.LOGGED;
                                        /*  se l'utente ha eseguito il login correttamente mantengo un suo riferimento in player*/
                                        player = users.get(msgToken[1]);
                                        break;
                                    case -1:
                                        this.out.println(msgType.NOTOK + " password errata!");
                                        break;
                                    default:
                                        break;
                                }
                            }
                            break;
                        case REGISTER:
                            switch (register(msgToken[1], msgToken[2])) {
                                case 0:
                                    this.out.println(msgType.NOTOK + " questo username esiste già.");
                                    break;
                                case 1:
                                    this.out.println(msgType.OK + " utente registrato con successo!");
                                    break;
                                default:
                                    break;
                            }
                            break;
                        case LOGOUT:
                            if(player!=null) {
                                player = null;
                                out.println(msgType.OK + " utente disconnesso.");
                            }
                            else
                                out.println(msgType.NOTOK +" non hai eseguito il login.");
                            break;
                        case PLAYWORDLE:
                            if(player!=null) {
                                if(player.remainingTrials == 0){
                                    out.println(msgType.NOTOK + " hai finito i tentativi.");
                                }
                                if(player.hasWonToday){
                                    out.println(msgType.NOTOK + " hai già vinto la partita di oggi!");
                                }
                                else {
                                    /*  se l'utente porta a termine la richiesta di giocare passa allo stato d'INGAME*/
                                    clientUserState = userState.INGAME;
                                    out.println(msgType.OK + " puoi giocare. Hai ancora " + this.player.remainingTrials + " tentativi.");
                                }
                            }
                            else
                                out.println(msgType.NOTOK + " non hai eseguito il login.");
                            break;
                        case SENDWORD:
                            /*  controllo se il giocatore è entrato in partita  */
                            if (clientUserState == userState.INGAME){
                                guessedWord = msgToken[1];
                                /*Se il giocatore ha indovinato la parola ha vinto e aggiorno le sue statistiche   */
                                if(guessedWord.equals(secretWord)) {
                                    /*  al primo tentativo corretto di SENDWORD incremento il numero di partite giocate */
                                    if(player.remainingTrials == 12)
                                        player.matchPlayed++;
                                    out.println(msgType.OK + " hai vinto!");
                                    player.wordSuggestions.add("++++++++++");
                                    player.guessDistribution.put(12 - player.remainingTrials + 1,player.guessDistribution.get(12 - player.remainingTrials + 1) +1);
                                    player.remainingTrials--;
                                    player.matchWon++;
                                    player.hasWonToday = true;
                                    player.lastMatchWon = true;
                                    player.lastStreak++;
                                    if (player.lastStreak > player.streakRecord)
                                        player.streakRecord = player.lastStreak;
                                    clientUserState = userState.LOGGED;
                                }
                                /*  controllo se la GuessedWord è nel vocabolario, in quel caso conteggio il tentativo  */
                                else if (checkVocabulary(guessedWord)){
                                    /*  al primo tentativo corretto di SENDWORD incremento il numero di partite giocate */
                                    if(player.remainingTrials == 12)
                                        player.matchPlayed++;
                                    player.remainingTrials--;
                                    stringCompared = new StringBuilder();
                                    guessedSplit = guessedWord.split("");
                                    secretWordSplit = secretWord.split("");
                                    /*  costruzione del suggerimento da inviare all'utente  */
                                    for (int i=0; i<10;i++){
                                        if (secretWordSplit[i].equals(guessedSplit[i]))
                                            stringCompared.append("+");
                                        else if (secretWord.contains(guessedSplit[i]))
                                            stringCompared.append("?");
                                        else
                                            stringCompared.append("x");
                                    }
                                    player.wordSuggestions.add(stringCompared.toString());
                                    /*  controllo dei tentativi rimanenti   */
                                    if (player.remainingTrials >0) {
                                        /*  se il giocatore non ha ancora esaurito i tentativi  */
                                        out.println(msgType.OK + " " + stringCompared + ": hai a disposizione " + player.remainingTrials + " tentativi.");
                                    }
                                    else{
                                        /*  se il giocatore ha esaurito i tentativi e ha parso la partita giornaliera   */
                                        clientUserState = userState.LOGGED;
                                        player.lastStreak = 0;
                                        player.lastMatchWon = false;
                                        out.println(msgType.OK + " " + stringCompared + ": hai finito i tentativi per oggi!");
                                    }
                                    /*Al termine aggiorno i dati dell'utente per renderli consistenti nel file users.json   */
                                    updateUsers();
                                }
                                else
                                    /*  se la parola non è nel vocabolario lo notifico al giocatore */
                                    out.println(msgType.NOTOK + " la tua guessed word non è nel vocabolario");
                            }
                            else
                                /*  se il giocatore non è in partita non può giocare    */
                                out.println(msgType.NOTOK + " non puoi giocare");
                            break;
                        case SENDSTATS:
                            /* controllo se l'utente ha eseguito il login */
                            if(player != null) {
                                /*  creazione di un messaggio con le statistiche dell'utente che ne fa richiesta    */
                                float winRate = (float) player.matchWon / player.matchPlayed;
                                StringBuilder playerStats = new StringBuilder();
                                playerStats.append(player.matchPlayed);
                                playerStats.append(" ").append(winRate);
                                playerStats.append(" ").append(player.lastStreak);
                                playerStats.append(" ").append(player.streakRecord);
                                for (int i=1;i<13;i++)
                                    playerStats.append(" ").append(player.guessDistribution.get(i));
                                out.println(msgType.OK + " " + playerStats);
                            }
                            else
                                out.println(msgType.NOTOK + " non hai eseguito il login.");
                            break;
                        case SHARE:
                            /*  controllo se l'utente ha eseguito il login  */
                            if(player==null)
                                out.println(msgType.NOTOK + " devi eseguire il login.");
                                /*  se il giocatore ha ancora 12 tentativi a disposizione non ha niente da condividere  */
                            else if(player.remainingTrials == 12)
                                out.println(msgType.NOTOK + " non hai ancora niente da condividere.");
                                /*  condivisione dei propri risultati agli altri giocatori  */
                            else {
                                StringBuilder wordSuggestions = new StringBuilder();
                                for (String e : player.wordSuggestions) {
                                    wordSuggestions.append(" ").append(e);
                                }
                                sendUDPMessage(player.username + wordSuggestions, udpAddress, udpPort);
                                out.println(msgType.OK + " i tuoi risultati sono stati condivisi.");
                            }
                            break;
                        case EXIT:
                            /*  invio del messaggio di terminazione */
                            sendUDPMessage("STOP",udpAddress,udpPort);
                            this.clientUserState = userState.INTERRUPTED;
                            out.println(msgType.OK + " arrivederci!");
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
    /*
     *   metodo utilizzato all'avvio del server per leggere le properties
     */
    public static void readConfig() throws IOException {
        InputStream input = new FileInputStream("server.properties");
        Properties prop = new Properties();
        prop.load(input);
        port = Integer.parseInt(prop.getProperty("port"));
        udpPort = Integer.parseInt(prop.getProperty("udpPort"));
        udpAddress = prop.getProperty("udpAddress");
        usersFile = prop.getProperty("usersFile");
        input.close();
    }
    public static void main(String[] args) throws IOException {
        /*  lettura delle properties del server per il corretto funzionamento   */
        readConfig();
        /*  apertura ServerSocket per accettare nuove richieste sulla porta port    */
        try(ServerSocket acceptSocket = new ServerSocket(port)) {
            try {
                getUsers();
            } catch (IOException e) {
                throw new RuntimeException();
            }
            /* creazione thread per eseguire operazioni di routine  */
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            Runnable task = new WordExtractor();
            int initialDelay = 0;
            int periodicDelay = (int) Duration.ofDays(1).toMinutes();
            scheduler.scheduleAtFixedRate(task, initialDelay, periodicDelay, TimeUnit.MINUTES);

            /*  creazione ThreadPool per creare thread di gestione client ClientHandler    */
            ExecutorService service = Executors.newCachedThreadPool();
            /*  ShutDownHook per gestire la terminazione di ExecutorService alla pressione di CTRL+C    */
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                /*  salvataggio dei dati degli utenti nel file JSON */
                try {
                    updateUsers();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                /*  ExecutorService smette di eseguire nuovi tasks  */
                service.shutdown();
                try {
                    /*  Attesa della terminazione dei tasks con timeout di QUATTRO secondi    */
                    if (!service.awaitTermination(4, TimeUnit.SECONDS)) {
                        System.out.println("ExecutorService non ha terminato i task nei tempi previsti.");
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }));
            while (true) {
                Socket socket;
                try {
                    socket = acceptSocket.accept();
                    service.execute(new ClientHandler(socket));
                } catch (SocketException e) {
                    e.printStackTrace();
                    break;
                }
            }
        }
    }
}
