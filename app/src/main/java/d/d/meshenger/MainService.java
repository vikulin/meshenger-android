package d.d.meshenger;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.goterl.lazycode.lazysodium.LazySodiumAndroid;
import com.goterl.lazycode.lazysodium.SodiumAndroid;
import com.goterl.lazycode.lazysodium.exceptions.SodiumException;
import com.goterl.lazycode.lazysodium.utils.Key;
import com.goterl.lazycode.lazysodium.utils.KeyPair;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.SurfaceViewRenderer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;


public class MainService extends Service implements Runnable {
    private Database db;

    public static final int serverPort = 10001;
    private ServerSocket server;

    private volatile boolean run = true;
    private volatile boolean interrupted = false;

    private RTCCall currentCall = null;

    @Override
    public void onCreate() {
        log("onCreate()");
        super.onCreate();
        db = new Database(this);

        new Thread(this).start();

        log("MainService started");

        LocalBroadcastManager.getInstance(this).registerReceiver(settingsReceiver, new IntentFilter("settings_changed"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("onDestroy()");
        String pubkeyData = db.getAppData().getPublicKey();
        List<Contact> contacts = db.getContacts();

        db.close();

        // shutdown listening socket
        if (server != null && server.isBound() && !server.isClosed()) {
            try {
                JSONObject request = new JSONObject();
                request.put("publicKey", pubkeyData);
                request.put("action", "status_change");
                request.put("status", "offline");
                for (Contact c : contacts) {
                    if (c.getState() == Contact.State.ONLINE) {
                        try {
                            Socket s = c.createSocket();
                            //  Socket s = new Socket(c.getAddress().replace("%zone", "%wlan0"), serverPort);
                            s.getOutputStream().write((request.toString() + "\n").getBytes());
                            s.getOutputStream().flush();
                            s.close();
                        } catch (Exception e) {}
                    }
                }
                server.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        LocalBroadcastManager.getInstance(this).unregisterReceiver(settingsReceiver);
        log("MainService stopped");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("onStartCommand()");
        return START_STICKY;
    }

    private void initNetwork() throws IOException {
        server = new ServerSocket(serverPort);
    }

    private void refreshContacts() {
        /*
        ArrayList<Contact> contacts = (ArrayList<Contact>) db.getContacts();
        if (db.getAppData() == null) {
            //userName = "Unknown";
            ignoreUnsaved = false;
        } else {
            //userName = db.getAppData().getUsername();
            if (db.getAppData().getBlockUC() == 1) {
                ignoreUnsaved = true;
            } else {
                ignoreUnsaved = false;
            }
        }
        */
    }

    private void mainLoop() throws IOException {
        while (run) {
            try {
                Socket s = server.accept();
                log("client " + s.getInetAddress().getHostAddress() + " connected");
                new Thread(() -> handleClient(s)).start();
            } catch (IOException e) {
                if (!interrupted) {
                    throw e;
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream os = client.getOutputStream();
            String line;

            log("waiting for line...");
            while ((line = reader.readLine()) != null) {
                log("line: " + line);
                JSONObject request = new JSONObject(line);
                String action = request.optString("action", "");
                log("action: " + action);

                switch (action) {
                    case "call": {
                        // someone calls us
                        log("ringing...");
                        byte[] nonce = Utils.hexStringToByteArray(request.optString("nonce", ""));
                        String encrypted = request.getString("offer");
                        String offer = decrypt(encrypted, nonce);
                        this.currentCall = new RTCCall(client, this, offer);

                        String publicKey = ""; //TODO
                        if (db.getAppData().getBlockUC() && !db.contactSaved(publicKey)) {
                            currentCall.decline();
                            continue;
                        };

                        String response = "{\"action\":\"ringing\"}\n";
                        os.write(response.getBytes());

                        Intent intent = new Intent(this, CallActivity.class);
                        intent.setAction("ACTION_ACCEPT_CALL");
                        intent.putExtra("EXTRA_USERNAME", request.getString("username"));
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return;
                    }
                    case "ping": {
                        // someone wants to know if we are online
                        String publickey = request.optString("publicKey", null);
                        setClientState(publickey, Contact.State.ONLINE);
                        JSONObject response = new JSONObject();
                        response.put("publicKey", this.db.getAppData().getPublicKey());
                        os.write((response.toString() + "\n").getBytes());
                        break;
                    }
                    case "connect": {
                        // transmit their public key after they scanned out QR code
                        String publickey = request.optString("publicKey", null);
                        if (publickey != null) {
                            String hostaddress = client.getInetAddress().getHostAddress();
                            Contact c = new Contact(
                                   // client.getInetAddress().getHostAddress(),
                                    request.getString("username"),
                                    "",
                                    request.getString("publicKey")
                            );
                            if (request.getJSONArray("connection_data") == null) {
                                return;
                            }
                            JSONArray connectionData = request.getJSONArray("connection_data");
                            JSONObject linkLocal = null;
                            for (int i = 0; i < connectionData.length(); i += 1) {
                                if (connectionData.getJSONObject(i).getString("type").equalsIgnoreCase("LinkLocal")) {
                                    linkLocal = connectionData.getJSONObject(i);
                                }
                            }
                            c.addConnectionData(new ConnectionData.LinkLocal(linkLocal.getString("mac_address"), MainService.serverPort));
                            c.addConnectionData(new ConnectionData.Hostname(hostaddress));
                            try {
                                db.insertContact(c);
                                //contacts.add(c);
                            } catch (Database.ContactAlreadyAddedException e) {
                                // ignore
                            }
                            //JSONObject response = new JSONObject();
                            //response.put("username", userName);
                            //os.write((response.toString() + "\n").getBytes());
                            Intent intent = new Intent("incoming_contact");
                            //intent.putExtra("extra_identifier", request.getString("identifier"));
                            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                        }
                        break;
                    }
                    case "status_change": {
                        String publickey = request.optString("publicKey", "");
                        if (request.optString("status", "").equals("offline")) {
                            setClientState(publickey, Contact.State.OFFLINE);
                        } else {
                            log("Received unknown status_change: " + request.optString("status", ""));
                        }
                    }
                }
            }

            log("client " + client.getInetAddress().getHostAddress() + " disconnected");
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("call_declined"));
        } catch (Exception e) {
            e.printStackTrace();
            log("client " + client.getInetAddress().getHostAddress() + " disconnected (exception)");
            //currentCall.decline();
        }
    }

    public String decrypt(String encrypted, byte[] nonce) throws SodiumException {
        LazySodiumAndroid ls = new LazySodiumAndroid(new SodiumAndroid());
        ArrayList<Contact> contacts = (ArrayList<Contact>) db.getContacts();
        String secretKey = db.getAppData().getSecretKey();
        Key secret_key = Key.fromHexString(secretKey);
        for (Contact c : contacts) {
            Key pub_key = Key.fromHexString(c.getPubKey());
            KeyPair decryptKeyPair = new KeyPair(pub_key, secret_key);
            String decrypted = ls.cryptoBoxOpenEasy(encrypted, nonce, decryptKeyPair);
            if (decrypted != null) {
                return decrypted;
            }
        }
        return null;
    }

    private void setClientState(String publickey, Contact.State state) {
        ArrayList<Contact> contacts = (ArrayList<Contact>) db.getContacts();
        for (Contact c : contacts) {
            if (c.matchEndpoint(publickey)) {
                c.setState(Contact.State.ONLINE);
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("contact_refresh"));
                break;
            }
        }
    }

    @Override
    public void run() {
        try {
            initNetwork();
            refreshContacts();
            mainLoop();
        } catch (IOException e) {
            e.printStackTrace();
            new Handler(getMainLooper()).post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            stopSelf();
            return;
        }
    }

    class MainBinder extends Binder {
        RTCCall startCall(Contact contact, RTCCall.OnStateChangeListener listener, SurfaceViewRenderer renderer) {
            return RTCCall.startCall(contact, listener, MainService.this);
        }

        RTCCall getCurrentCall() {
            return currentCall;
        }

        String getPublicKey() {
            return db.getAppData().getPublicKey();
        }

        String getUsername() {
            return db.getAppData().getUsername();
        }

        void addContact(Contact c) {
            try {
                db.insertContact(c);
                //contacts.add(c);
                log("adding contact " + c.getPubKey() + "  " + c.getId());

            } catch (Database.ContactAlreadyAddedException e) {
                Toast.makeText(MainService.this, "Contact already added", Toast.LENGTH_SHORT).show();
            }
            new Thread(new ConnectRunnable(c)).start();
        }

        void deleteContact(Contact c){
            db.deleteContact(c);
            refreshContacts();
        }

        void updateContact(Contact c){
            db.updateContact(c);
            refreshContacts();
        }

        void pingContacts(List<Contact> c, ContactPingListener listener) {
            new Thread(new PingRunnable(c, listener)).start();
        }

        public List<Contact> getContacts() {
            return MainService.this.db.getContacts();
            //return MainService.this.contacts;
        }

        /*void setPingResultListener(ContactPingListener listener) {
            MainService.this.pingListener = listener;
        }*/
    }

    class PingRunnable implements Runnable {
        private List<Contact> contacts;
        ContactPingListener listener;

        PingRunnable(List<Contact> contacts, ContactPingListener listener) {
            this.contacts = contacts;
            this.listener = listener;
        }

        @Override
        public void run() {
            for (Contact c : contacts) {
                try {
                    ping(c);
                    c.setState(Contact.State.ONLINE);
                    log("client " + c.getPubKey() + " online");
                } catch (Exception e) {
                    c.setState(Contact.State.OFFLINE);
                    log("client " + c.getPubKey() + " offline");
                    //e.printStackTrace();
                } finally {
                    if (listener != null) {
                        listener.onContactPingResult(c);
                    } else {
                        log("no listener");
                    }
                }
            }
        }

        private void ping(Contact c) throws Exception {
            log("ping");
            //  List<String> targets = getAddressPermutations(c);
            //  log("targets: " + targets.size());
            Socket s = null;
            //for (String target : targets) {
            try {
                // log("opening socket to " + target);
                //s = new Socket(target.replace("%zone", "%wlan0"), serverPort);
                log("opening socket to " + c.getPubKey());
                //OutputStream os = s.getOutputStream();
                //os.write(("{\"action\":\"ping\",\"publicKey\":\"" + pubkeyData + "\"}\n").getBytes());

                //BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
                //String line = reader.readLine();
                //JSONObject object = new JSONObject(line);
                 /*   String responseMac = object.getString("identifier");
                    if (!responseMac.equals(c.getIdentifier())) {
                        throw new Exception("foreign contact");
                    }
                */
                //s.close();

                //   c.setAddress(target);

                return;
            } catch (Exception e) {
                //   continue;
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception e){}
                }
            }
            // }

            throw new Exception("contact not reachable");
        }
    }

   /* private List<String> getAddressPermutations(Contact c) {
        ArrayList<InetAddress> mutationAddresses = new ArrayList<>();
        byte[] eui64 = Utils.getEUI64();
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : all) {
                if (!networkInterface.getName().equalsIgnoreCase("wlan0")) continue;
                List<InterfaceAddress> addresses = networkInterface.getInterfaceAddresses();
                loop:
                for (InterfaceAddress address : addresses) {
                    if (address.getAddress().isLoopbackAddress()) continue;
                    if (address.getAddress() instanceof Inet6Address) {
                        byte[] bytes = address.getAddress().getAddress();
                        for (int i = 0; i < 8; i += 1) {
                            if (bytes[i + 8] != eui64[i]) continue loop;
                        }
                        mutationAddresses.add(address.getAddress());
                        Log.d(BuildConfig.APPLICATION_ID, "found matching address: " + address.getAddress().getHostAddress());
                    }
                }
                break;
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        log("loop ended");
        byte[] targetEUI = addressToEUI64(c.getIdentifier());
        log("target: " + Utils.formatAddress(targetEUI));
        ArrayList<String> result = new ArrayList<>();
        int i = 0;
        for (InetAddress address : mutationAddresses) {
            log("mutating address: " + address.getHostAddress());
            byte[] add = address.getAddress();
            System.arraycopy(targetEUI, 0, add, 8, 8);
            try {
                address = Inet6Address.getByAddress(address.getHostAddress(), add, ((Inet6Address) address).getScopeId());
            } catch (UnknownHostException e) {
                continue;
            }
            log("mutated address: " + address.getHostAddress());
            result.add(address.getHostAddress());
        }

        if (!result.contains(c.getName())) {
            result.add(c.getName());
        } else {
            log("address duplicate");
        }
        return result;
    }
    */

    class ConnectRunnable implements Runnable {
        private Contact contact;

        ConnectRunnable(Contact contact) {
            this.contact = contact;
        }


  /*      private String address;
        private String username;
        private String challenge;
        private String identifier;

        ConnectRunnable(Contact contact, String challenge) {
            this.address = contact.getAddress();
            this.username = userName;
            this.challenge = challenge;
            this.identifier = Utils.formatAddress(Utils.getMacAddress());
        }
        */


        @Override
        public void run() {
            try {
                //  Socket s = new Socket(address.replace("%zone", "%wlan0"), serverPort);
                Socket s = this.contact.createSocket();
                OutputStream os = s.getOutputStream();
                JSONObject object = new JSONObject();

                object.put("action", "connect");
                //object.put("username", username);
                //object.put("identifier", identifier);
                object.put("data", Contact.exportJSON(this.contact));

                log("request: " + object.toString());

                os.write((object.toString() + "\n").getBytes());
                os.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
                log("awaiting response...");
                String line = reader.readLine();
                log("contact: " + line);
                s.close();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    private BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            /*
            switch (intent.getAction()) {
                case "settings_changed": {
                    String subject = intent.getStringExtra("subject");
                    switch (subject) {
                        case "username": {
                            userName = intent.getStringExtra("username");
                            log("username: " + userName);
                            break;
                        }
                        case "ignoreUnsaved":{
                            ignoreUnsaved = intent.getBooleanExtra("ignoreUnsaved", false);
                            log("ignore: " + ignoreUnsaved);
                            break;
                        }
                    }
                }
            }*/
        }
    };

    public interface ContactPingListener {
        void onContactPingResult(Contact c);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        log("onBind()");
        return new MainBinder();
    }

    private void log(String data) {
        Log.d(MainService.class.getSimpleName(), data);
    }
}
