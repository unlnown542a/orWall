package org.ethack.orwall.lib;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import android.util.Log;

import org.ethack.orwall.BackgroundProcess;
import org.sufficientlysecure.rootcommands.Shell;
import org.sufficientlysecure.rootcommands.command.SimpleCommand;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public class Iptables {
    public final static String DIR_DST = "/system/etc/init.d";
    public final static String DST_FILE = String.format("%s/91firewall", DIR_DST);
    public final static String DIR_DST_1 = "/data/local/userinit.d/";
    public final static String DST_FILE_1 = String.format("%s/91firewall", DIR_DST_1);

    private Context context;
    private Boolean _supportComment;
    private Boolean _supportWait;
    private Integer _orbotUID;
    private Shell shell = null;

    /**
     * Construtor
     *
     * @param context
     */
        public Iptables(Context context) {
        this.context = context;
    }

    public boolean getSupportComment(){
        if (_supportComment == null)
            _supportComment = runCommand("cat /proc/net/ip_tables_matches | grep -q comment");
        return _supportComment;
    }

    public boolean getSupportWait(){
        if (_supportWait == null)
            _supportWait = runCommand("iptables --help | grep -q -e \"--wait\"");
        return _supportWait;
    }

    public int getOrbotUID(){
        if (_orbotUID == null)
            //_orbotUID = Util.getOrbotUID(context);
            _orbotUID = 0;
        return _orbotUID;
    }

    public boolean isOrbotInstalled(){
        return getOrbotUID() >= 0;
    }

    /**
     * run a simple command
     *
     * @param command
     * @return true if success
     */
    private boolean runCommand(final String command) {
        if (shell == null){
            try {
                shell = Shell.startRootShell();
            } catch (IOException e) {
                Log.e("Shell", "NO shell !");
            }
        }

        if (this.shell != null) {
            SimpleCommand cmd = new SimpleCommand(command);
            try {
                this.shell.add(cmd).waitForFinish();
                return (cmd.getExitCode() == 0);
            } catch (IOException e) {
                Log.e("Shell", "Unable to run simple command");
                Log.e("Shell", command);
                Log.e("Trace", e.getMessage());
            } catch (TimeoutException e) {
                Log.e("Shell", "A timeout was reached");
                Log.e("Shell", e.getMessage());
            }
        }
        return false;
    }

    /**
     * This method is called upon device boot, or when we re-enable orWall
     * It adds new chains, and some rules in order to get iptables up n'running.
     */
    public void boot() {
        Log.d("Boot: ", "Deactivate some stuff at boot time in order to prevent crashes");
        Preferences.setBrowserEnabled(context, false);
        Preferences.setOrwallEnabled(context, true);

        // initialize main chains
        initIPv6();
        initOutputs();
        initInput();

        // get lan subnet
        LANPolicy();


        if (Preferences.isSIPEnabled(this.context)) {
            Long sip_uid = Long.valueOf(Preferences.getSIPApp(this.context));
            if (sip_uid != 0) {
                Log.d("Boot", "Authorizing SIP");
                manageSip(true, sip_uid);
            }
        }

        if (Preferences.isADBEnabled(context)) {
            enableADB(true);
        }

        if (Preferences.isSSHEnabled(context)) {
            enableSSH(true);
        }

        Log.d("Boot: ", "Finished initialization");

        Log.d("Boot: ", "Preparing NAT stuff");
        NatRules natRules = new NatRules(context);
        Log.d("Boot: ", "Get NAT rules...");
        ArrayList<AppRule> rules = natRules.getAllRules();
        Log.d("Boot: ", "Length received: " + String.valueOf(rules.size()));

        // Use internal queuing
        final Intent bgpProcess = new Intent(this.context, BackgroundProcess.class);

        for (AppRule rule : rules) {
            rule.install(this.context, bgpProcess);
            Log.d("Boot: ", "pushed new app in queue: " + rule.getPkgName());
        }
        Log.d("Boot: ", "Finished NAT stuff");
    }

    /**
     * This method will deactivate the whole orWall iptables stuff.
     * It must:
     * - set INPUT and OUTPUT policy to ACCEPT
     * - remove any chain it created (though we want to keep the "witness" chain).
     */

    public void deactivate() {
        String[] rules = {
                // set OUTPUT policy back to ACCEPT
                "-P OUTPUT ACCEPT",
                // flush all OUTPUT rules
                "-D OUTPUT -j ow_OUTPUT",
                "-F ow_OUTPUT",
                "-X ow_OUTPUT",
                // set INPUT policy back to ACCEPT
                "-P INPUT ACCEPT",
                // flush all INPUT rules
                "-D INPUT -j ow_INPUT",
                "-F ow_INPUT",
                "-X ow_INPUT",
                // flush nat OUTPUT
                "-t nat -D OUTPUT -j ow_OUTPUT",
                "-t nat -F ow_OUTPUT",
                "-t nat -X ow_OUTPUT",
                // flush LAN
                "-F ow_LAN",
                "-X ow_LAN"
        };
        for (String rule : rules) {
            if (!genericRule(rule)) {
                Log.e("deactivate", "Unable to remove rule");
                Log.e("deactivate", rule);
            }
        }

        // subnet & tethering is no more in iptables
        Preferences.cleanIptablesPreferences(context);
    }

    public void deactivateV6() {
        String[] rules = {
                "-P INPUT ACCEPT",
                "-P OUTPUT ACCEPT",
                "-P FORWARD ACCEPT",
                "-D INPUT -j REJECT",
                "-D OUTPUT -j REJECT",
                "-D FORWARD -j REJECT"
        };
        for (String rule : rules) {
            if (!genericRuleV6(rule)) {
                Log.e("deactivate", "Unable to remove IPv6 rule");
                Log.e("deactivate", rule);
            }
        }
    }

    /**
     * Checks if iptables binary is on the device.
     * @return true if it finds iptables
     */
    public static boolean iptablesExists() {
        File iptables = new File(Constants.IPTABLES);
        return iptables.exists();
    }

    public static boolean ip6tablesExists() {
        File iptables = new File(Constants.IP6TABLES);
        return iptables.exists();
    }

    /**
     * Checks if iptables was successfully initialized by the init-script.
     * @return true if it finds the witness chain.
     */
    public boolean isInitialized() {
        String rule = "-C ow_OUTPUT_LOCK -j DROP";
        return genericRule(rule);
    }

    public boolean haveBooted() {
        String rule = "-C OUTPUT -j ow_OUTPUT";
        return genericRule(rule);
    }

    /**
     * update rules for LAN access.
     */
    public void LANPolicy() {
        String subnet = NetworkHelper.getSubnet(this.context);

        // Get subnet from SharedPreferences
        String old_subnet = Preferences.getCurrentSubnet(context);

        if (old_subnet != null && !old_subnet.equals(subnet)) {
            // Remove rules if we got another subnet in sharedPref
            LanNoNat(old_subnet, false);
            Preferences.setCurrentSubnet(context, null);
        }

        if (subnet != null && !subnet.equals(old_subnet)) {
            // Do what's needed with current subnet
            LanNoNat(subnet, true);

            // Or save new subnet
            Preferences.setCurrentSubnet(context, subnet);
        }
    }

    /**
     * Apply or remove rules for ADB access
     * @param allow boolean, true if we want to add rules, false otherwise.
     */
    public void enableADB(final boolean allow) {
        char action = (allow ? 'I' : 'D');

        // TODO: lock in order to authorize only LAN
        String[] rules = {
                "-%c ow_INPUT -p tcp --dport 5555 -m conntrack --ctstate NEW,ESTABLISHED -j ACCEPT",
                "-%c ow_OUTPUT -p tcp --sport 5555 -m conntrack --ctstate ESTABLISHED -j ACCEPT",
                "-t nat -%c ow_OUTPUT -p tcp --sport 5555 -j RETURN",
        };

        for (String rule : rules) {
            if (!genericRule(String.format(rule, action))) {
                Log.e("enableADB", "Unable to add rule");
                Log.e("enableADB", String.format(rule, action));
            }
        }
    }

    /**
     * Apply or remove rules for SSH access
     * @param allow boolean, true if we want to add rules, false otherwise.
     */
    public void enableSSH(final boolean allow) {
        char action = (allow ? 'I' : 'D');

        // TODO: lock in order to authorize only LAN
        // TODO: better way to implement this kind of opening (copy-paste isn't a great way)
        // Have to think a bit more about that.
        String[] rules = {
                "-%c ow_INPUT -p tcp --dport 22 -m conntrack --ctstate NEW,ESTABLISHED -j ACCEPT",
                "-%c ow_OUTPUT -p tcp --sport 22 -m conntrack --ctstate ESTABLISHED -j ACCEPT",
                "-t nat -%c ow_OUTPUT -p tcp --sport 22 -j RETURN",
        };

        for (String rule : rules) {
            if (!genericRule(String.format(rule, action))) {
                Log.e("enableSSH", "Unable to add rule");
                Log.e("enableSSH", String.format(rule, action));
            }
        }
    }

    /**
     * fix IPv6 leak
     */

    public void initIPv6(){
        if (!ip6tablesExists()) return;
        if (genericRuleV6("-C INPUT -j REJECT")) return;

        String[] rules = {
                // flush all OUTPUT rules
                "-P INPUT DROP",
                "-P OUTPUT DROP",
                "-P FORWARD DROP",
                "-I INPUT -j REJECT",
                "-I OUTPUT -j REJECT",
                "-I FORWARD -j REJECT"
        };
        for (String rule : rules) {
            if (!genericRuleV6(rule)) {
                Log.e(Iptables.class.getName(), "Unable to initialize IPv6");
                Log.e(Iptables.class.getName(), rule);
            }
        }
    }

    /**
     * Initialize OUTPUT chain in order to allow orbot network to go out
     */
    public void initOutputs() {
        int orbot_uid = getOrbotUID();
        Long dns_proxy = Long.valueOf(Preferences.getDNSPort(context));
        ArrayList<String> rules = new ArrayList<>();
        rules.add("-P OUTPUT DROP");
        rules.add("-N ow_OUTPUT");
        rules.add("-A OUTPUT -j ow_OUTPUT");
        if (orbot_uid > 0){
            // let orbot output
            rules.add(String.format(Locale.US,
                    "-A ow_OUTPUT -m owner --uid-owner %d -m conntrack --ctstate NEW,RELATED,ESTABLISHED -j ACCEPT%s",
                    orbot_uid, (getSupportComment() ? " -m comment --comment \"Allow Orbot outputs\"" : "")
            ));
            // accept redirected system dns queries
            rules.add(String.format(Locale.US,
                    "-A ow_OUTPUT -m owner --uid-owner 0 -d 127.0.0.1/32 -m conntrack --ctstate NEW,RELATED,ESTABLISHED -p udp -m udp --dport %d -j ACCEPT%s",
                    dns_proxy, (getSupportComment() ? " -m comment --comment \"Allow System DNS queries\"" : "")
            ));
        } else {
            // can't redirect system dns query, we have to accept them ... for now
            rules.add(String.format(Locale.US,
                    "-A ow_OUTPUT -m owner --uid-owner 0 -p udp -m udp --dport 53 -j ACCEPT",
                    dns_proxy, (getSupportComment() ? " -m comment --comment \"ACCEPT System DNS queries\"" : "")
            ));
        }
        // name output chaine on nat
        rules.add("-t nat -N ow_OUTPUT");
        // do not redirect localhost addresses
        rules.add("-t nat -A ow_OUTPUT -d 127.0.0.1/32 -j RETURN");
        if (orbot_uid > 0) {
            // do not redirect orbot
            rules.add(String.format(Locale.US,
                    "-t nat -A ow_OUTPUT -m owner --uid-owner %d -j RETURN%s",
                    orbot_uid, (getSupportComment() ? " -m comment --comment \"Orbot bypasses itself.\"" : "")
            ));
            // Redirect system dsn queries to TOR
            rules.add(String.format(Locale.US,
                    "-t nat -A ow_OUTPUT -m owner --uid-owner 0 -p udp -m udp --dport 53 -j REDIRECT --to-ports %d%s",
                    dns_proxy, (getSupportComment() ? " -m comment --comment \"Allow DNS queries\"" : "")
            ));
        }

        // apply rules in the chain
        rules.add("-t nat -A OUTPUT -j ow_OUTPUT");
        // create a chain for LAN
        rules.add("-N ow_LAN");
        // at the end, deactivate boot locking
        rules.add("-D OUTPUT -j ow_OUTPUT_LOCK");

        for (String rule : rules) {
            if (!genericRule(rule)) {
                Log.e(Iptables.class.getName(), "Unable to initialize");
                Log.e(Iptables.class.getName(), rule);
            }
        }
    }

    /**
     * Initialize INPUT chain
     */
    public void initInput() {
        int orbot_uid = getOrbotUID();
        ArrayList<String> rules = new ArrayList<>();

        rules.add("-P INPUT DROP");
        rules.add("-N ow_INPUT");
        rules.add("-A INPUT -j ow_INPUT");
        if (orbot_uid > 0){
            // accept orbot inputs
            rules.add(String.format(Locale.US,
                    "-A ow_INPUT -m owner --uid-owner %d -m conntrack --ctstate NEW,RELATED,ESTABLISHED -j ACCEPT%s",
                    orbot_uid, (getSupportComment() ? " -m comment --comment \"Allow Orbot inputs\"" : "")
            ));
        }
        rules.add(String.format(Locale.US,
                "-A ow_INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT%s",
                (getSupportComment() ? " -m comment --comment \"Allow related,established inputs\"" : "")
        ));
        // at the end, deactivate boot locking
        rules.add("-D INPUT -j ow_INPUT_LOCK");


        for (String rule : rules) {
            if (!genericRule(rule)) {
                Log.e(Iptables.class.getName(), "Unable to initialize");
                Log.e(Iptables.class.getName(), rule);
            }
        }
    }

    /**
     * Check if init-script is supported by the user device OS
     * It also save this state for later reference if needed
     * @return true if init is supported.
     */
    public static boolean initSupported() {
        File dstDir = new File(DIR_DST);
        return dstDir.exists();
    }

    /**
     * Checks some system settings before calling the method installing for good the init-script
     */
    public static void installInitScript(Context context) {

        final String src_file = new File(context.getDir("bin", 0), "userinit.sh").getAbsolutePath();

        CheckSum check_src = new CheckSum(src_file);
        CheckSum check_dst = new CheckSum(DST_FILE);

        if (initSupported()) {

            if (!check_dst.hash().equals(check_src.hash())) {
                doInstallScripts(src_file, DST_FILE);
            }
            File local_dst = new File(DIR_DST_1);
            if (local_dst.exists()) {
                CheckSum check_dst1 = new CheckSum(DST_FILE_1);
                if (!check_dst1.hash().equals(check_src.hash())) {
                    doInstallScripts(src_file, DST_FILE_1);
                }
            }
            Preferences.setEnforceInitScript(context, true);
        } else {
            Preferences.setEnforceInitScript(context, false);
        }
    }

    /**
     * Really install init-script (copies it from app RAW directory)
     * @param src_file String matching source init-script
     * @param dst_file String matching destination init-script
     */
    private static void doInstallScripts(String src_file, String dst_file) {
        Shell shell;
        try {
            shell = Shell.startRootShell();
        } catch (IOException e) {
            Log.e("Shell", "Unable to get shell");
            return;
        }

        if (shell != null) {
            String CMD = String.format("cp %s %s", src_file, dst_file);

            SimpleCommand command1 = new SimpleCommand("mount -o remount,rw /system");
            SimpleCommand command2 = new SimpleCommand(CMD);
            CMD = String.format("chmod 0755 %s", dst_file);
            SimpleCommand command3 = new SimpleCommand(CMD);
            SimpleCommand command4 = new SimpleCommand("mount -o remount,ro /system");
            try {
                shell.add(command1).waitForFinish();
                shell.add(command2).waitForFinish();
                shell.add(command3).waitForFinish();
                shell.add(command4).waitForFinish();
            } catch (IOException e) {
                Log.e("Shell", "Unable to run simple command");
            } catch (TimeoutException e) {
                Log.e("Shell", "Error while closing the Shell");
            } finally {
                try {
                    shell.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * Removes init-script.
     */
    public static void removeIniScript(Context context) {
        Shell shell;
        try {
            shell = Shell.startRootShell();
        } catch (IOException e) {
            Log.e("Shell", "Unable to get shell");
            return;
        }
        if (shell != null) {
            SimpleCommand command1 = new SimpleCommand("mount -o remount,rw /system");
            SimpleCommand command2 = new SimpleCommand("rm -f " + DST_FILE);
            SimpleCommand command3 = new SimpleCommand("mount -o remount,ro /system");
            try {
                shell.add(command1).waitForFinish();
                shell.add(command2).waitForFinish();
                shell.add(command3).waitForFinish();
            } catch (IOException e) {
                Log.e("Shell", "Unable to run simple command");
            } catch (TimeoutException e) {
                Log.e("Shell", "Error while closing the Shell");
            } finally {
                Preferences.setEnforceInitScript(context, false);
                try {
                    shell.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * Apply or remove rules for SIP bypass
     * @param status Boolean, true if we want to add rules, false otherwise
     * @param uid Long, application UID
     */
    public void manageSip(boolean status, Long uid) {
        String[] rules = {
                "-%c ow_INPUT -m owner --uid-owner %d -m conntrack --ctstate RELATED,ESTABLISHED -p udp -j ACCEPT",
                "-%c ow_OUTPUT -m owner --uid-owner %d -p udp -j ACCEPT",
                "-t nat -%c ow_OUTPUT -m owner --uid-owner %d -p udp -j RETURN",
        };
        char action = (status ? 'A' : 'D');

        for (String rule : rules) {
            genericRule(String.format(rule, action, uid));
        }
    }

    /**
     * Apply or remove rules enabling a browser to perform a network login in a captive network
     * @param status boolean, true if we want to enable this probe.
     * @param uid long, application UID
     */
    public void manageCaptiveBrowser(boolean status, Long uid) {
        String[] rules = {
                "-%c ow_INPUT -m owner --uid-owner %d -m conntrack --ctstate RELATED,ESTABLISHED -p udp --sport 53 -j ACCEPT",
                "-%c ow_INPUT -m conntrack --ctstate RELATED,ESTABLISHED -m owner --uid-owner %d -j ACCEPT",
                "-%c ow_OUTPUT -m owner --uid-owner %d -j ACCEPT",
                "-%c ow_OUTPUT -m owner --uid-owner %d -m conntrack --ctstate ESTABLISHED -j ACCEPT",
                "-t nat -%c ow_OUTPUT -m owner --uid-owner %d -j RETURN",
                "-t nat -%c ow_OUTPUT -m owner --uid-owner 0 -p udp -m udp --dport 53 -j RETURN"
        };
        char action = (status ? 'I' : 'D');

        for (String rule : rules) {
            Log.d("ManageCaptiveBrowser", String.format(rule, action, uid));
            genericRule(String.format(rule, action, uid));
        }
    }

    public void tetherUpdate(Context context, Set<String> before, Set<String> after){
        Preferences.setTetherInterfaces(context, after);

        if (before != null) {
            for (String item: before){
                if (!after.contains(item))
                    tether(false, item);
            }
        }

        for (String item: after){
            if (before == null || !before.contains(item))
                tether(true, item);
        }
    }

    public void tether(boolean status, String intf){

        char action = (status ? 'A' : 'D');
        ArrayList<String> rules = new ArrayList<>();

        // tether DHCP
        rules.add(
                String.format(
                        "-%c ow_INPUT -i %s -p udp -m udp --dport 67 -j ACCEPT%s",
                        action, intf, (getSupportComment() ? " -m comment --comment \"Allow DHCP tethering\"" : "")
                ));
        rules.add(
                String.format(
                        "-%c ow_OUTPUT -o %s -p udp -m udp --sport 67 -j ACCEPT%s",
                        action, intf, (getSupportComment() ? " -m comment --comment \"Allow DHCP tethering\"" : "")

                ));

        // tether DNS
        rules.add(
                String.format(
                        "-%c ow_INPUT -i %s -p udp --dport 53 -j ACCEPT%s",
                        action, intf, (getSupportComment() ? " -m comment --comment \"Allow DNS tethering\"" : "")
                ));
        rules.add(
                String.format(
                        "-%c ow_OUTPUT -o %s -p udp --sport 53 -j ACCEPT%s",
                        action, intf, (getSupportComment() ? " -m comment --comment \"Allow DNS tethering\"" : "")
                ));

        // relay dns query to isp
        rules.add(
                String.format(
                        "-%c ow_OUTPUT -m owner --gid-owner %s -p udp --dport 53 -j ACCEPT%s",
                        action, "nobody", (getSupportComment() ? " -m comment --comment \"Allow DNS/ISP tethering\"" : "")
                ));

        for (String rule : rules) {
            if (!genericRule(rule)) {
                Log.e("Tethering", "Unable to apply rule");
                Log.e("Tethering", rule);
            }
        }

        // this is a local network

        String mask;
        if (status){
            mask = NetworkHelper.getMask(intf);
            Preferences.setTetherNetwork(context, intf, mask);
        }
        else
            mask = Preferences.getTetherNetwork(context, intf);
        LanNoNat(mask, status);
    }


/*
    /* still not work
    public void tether_tor(Context context, boolean status, String intf){
        char action = (status ? 'I' : 'D');
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFERENCES, Context.MODE_PRIVATE);
        long trans_port = Long.valueOf(prefs.getString(Constants.PREF_TRANS_PORT, String.valueOf(Constants.ORBOT_TRANSPROXY)));
        long dns_port = Long.valueOf(prefs.getString(Constants.PREF_DNS_PORT, String.valueOf(Constants.ORBOT_DNS_PROXY)));
        ArrayList<String> rules = new ArrayList<>();
        rules.add(String.format("-t nat -%c PREROUTING -i %s -p udp --dport 53 -j REDIRECT --to-ports %s", action, intf, dns_port));
        rules.add(String.format(" -t nat -%c PREROUTING -i %s -p tcp -j REDIRECT --to-ports %s", action, intf, trans_port));
        for (String rule : rules) {
            if (!genericRule(rule)) {
                Log.e("Tor tethering", "Unable to apply rule");
                Log.e("Tor tethering", rule);
            }
        }
    }
*/

    /**
     * Build an iptables call in order to either create or remove NAT rule
     *
     * @param context
     * @param appUID
     * @param action
     * @param appName
     */
    public void natApp(Context context, final long appUID, final char action, final String appName) {
        if (!isOrbotInstalled()) return;
        long trans_port = Long.valueOf(Preferences.getTransPort(context));
        long dns_port = Long.valueOf(Preferences.getDNSPort(context));
        String[] RULES = {
                String.format(Locale.US,
                        "-t nat -%c ow_OUTPUT -p tcp -m tcp --tcp-flags FIN,SYN,RST,ACK SYN -m owner --uid-owner %d -j REDIRECT --to-ports %d%s",
                        action, appUID, trans_port,
                        (getSupportComment() ? String.format(" -m comment --comment \"Force %s through TransPort\"", appName) : "")
                ),
                String.format(Locale.US,
                        "-t nat -%c ow_OUTPUT -p udp --dport 53 -m owner --uid-owner %d -j REDIRECT --to-ports %d%s",
                        action, appUID, dns_port,
                        (getSupportComment() ? String.format(" -m comment --comment \"Force %s through DNSProxy\"", appName) : "")
                ),
                String.format(Locale.US,
                        "-%c ow_OUTPUT -d 127.0.0.1 -m conntrack --ctstate NEW,ESTABLISHED -m owner --uid-owner %d -m tcp -p tcp --dport %d -j ACCEPT%s",
                        action, appUID, trans_port,
                        (getSupportComment() ? String.format(" -m comment --comment \"Allow %s through TransPort\"", appName) : "")
                ),
                String.format(Locale.US,
                        "-%c ow_OUTPUT -d 127.0.0.1 -m conntrack --ctstate NEW,ESTABLISHED -m owner --uid-owner %d -p udp --dport %d -j ACCEPT%s",
                        action, appUID, dns_port,
                        (getSupportComment() ? String.format(" -m comment --comment \"Allow %s through DNSProxy\"", appName) : "")
                ),
        };

        for (String rule : RULES) {
            if (!genericRule(rule)) {
                Log.e(Iptables.class.getName(), rule);
            }
        }
    }

    public void LanNoNat(final String lan, final boolean allow) {
        char action = (allow ? 'I' : 'D');

        String[] rules = {
                "-%c ow_OUTPUT -d %s -j ow_LAN",
                "-%c ow_INPUT -s %s -j ow_LAN",
                "-t nat -%c ow_OUTPUT -d %s -j RETURN",
        };

        String formatted;
        for (String rule : rules) {
            formatted = String.format(rule, action, lan);
            if (!genericRule(formatted)) {
                Log.e(
                        "LanNoNat",
                        "Unable to add rule: " + formatted
                );
            }
        }
    }

    public boolean genericRule(final String rule) {
        return runCommand(String.format((getSupportWait())?"%s -w %s":"%s %s", Constants.IPTABLES, rule));
    }

    public boolean genericRuleV6(final String rule) {
        return runCommand(String.format((getSupportWait())?"%s -w %s":"%s %s", Constants.IP6TABLES, rule));
    }

    public void bypass(final long appUID, final String appName, final boolean allow) {
        char action = (allow ? 'A' : 'D');
        String[] rules = {
                String.format(Locale.US,
                        "-%c ow_OUTPUT -m conntrack --ctstate NEW,ESTABLISHED,RELATED -m owner --uid-owner %d -j ACCEPT%s",
                        action, appUID,
                        (getSupportComment() ? String.format(" -m comment --comment \"Allow %s to bypass Proxies\"", appName) : "")
                ),
        };

        for (String rule : rules) {
            if (!genericRule(rule)) {
                Log.e(
                        "bypass",
                        "Unable to add rule: " + rule
                );
            }
        }
    }

    public void localHost(final long appUID, final String appName, final boolean allow) {
        char action = (allow ? 'A' : 'D');

        String[] rules = {
                String.format(Locale.US,
                        "-%c ow_OUTPUT -o lo -m owner --uid-owner %d -j ACCEPT%s",
                        action, appUID,
                        (getSupportComment() ? String.format(" -m comment --comment \"Allow %s to connect on localhost\"", appName) : "")
                ),
                String.format(Locale.US,
                        "-%c ow_INPUT -i lo -m owner --uid-owner %d -j ACCEPT%s",
                        action, appUID,
                        (getSupportComment() ? String.format(" -m comment --comment \"Allow %s to connect on localhost\"", appName) : "")
                ),
        };

        for (String rule : rules) {
            if (!genericRule(rule)) {
                Log.e(
                        "localhost",
                        "Unable to add rule: " + rule
                );
            }
        }
    }

    public void localNetwork(final long appUID, final String appName, final boolean allow) {
        char action = (allow ? 'I' : 'D');

        String[] rules = {
                String.format(Locale.US,
                        "-%c ow_LAN -m owner --uid-owner %d -j ACCEPT%s",
                        action, appUID,
                        (getSupportComment() ? String.format(" -m comment --comment \"Local network %s\"", appName) : "")
                )
        };

        for (String rule : rules) {
            if (!genericRule(rule)) {
                Log.e(
                        "localnetwork",
                        "Unable to add rule: " + rule
                );
            }
        }
    }

}
