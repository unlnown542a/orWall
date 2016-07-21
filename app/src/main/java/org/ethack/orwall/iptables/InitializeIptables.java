package org.ethack.orwall.iptables;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import org.ethack.orwall.BackgroundProcess;
import org.ethack.orwall.lib.AppRule;
import org.ethack.orwall.lib.CheckSum;
import org.ethack.orwall.lib.Constants;
import org.ethack.orwall.lib.NatRules;
import org.ethack.orwall.lib.NetworkHelper;
import org.ethack.orwall.lib.Preferences;
import org.sufficientlysecure.rootcommands.Shell;
import org.sufficientlysecure.rootcommands.command.SimpleCommand;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.TimeoutException;

/**
 * Initialize IPTables. The application has
 * to run at least once before this can be called.
 * This initialization is the second steps needed in order to get
 * Orbot working.
 */
public class InitializeIptables {

    public final static String dir_dst = "/system/etc/init.d";
    public final static String dst_file = String.format("%s/91firewall", dir_dst);
    public final static String dir_dst1 = "/data/local/userinit.d/";
    public final static String dst_file1 = String.format("%s/91firewall", dir_dst1);
    private final IptRules iptRules;
    private long dns_proxy;
    private Context context;
    private boolean supportComment;

    /**
     * Construtor
     *
     * @param context
     */
    public InitializeIptables(Context context) {
        this.dns_proxy = Long.valueOf(Preferences.getDNSPort(context));
        this.context = context;
        this.supportComment = Preferences.isSupportComments(context);
        this.iptRules = new IptRules(this.supportComment);
    }

    /**
     * This method is called upon device boot, or when we re-enable orWall
     * It adds new chains, and some rules in order to get iptables up n'running.
     */
    public void boot() {
        boolean authorized;
        Long app_uid;
        PackageManager packageManager = context.getPackageManager();

        try {
            app_uid = (long) packageManager.getApplicationInfo(Constants.ORBOT_APP_NAME, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(BroadcastReceiver.class.getName(), "Unable to get Orbot real UID — is it still installed?");
            app_uid = (long) 0; // prevents stupid compiler error… never used.
            //android.os.Process.killProcess(android.os.Process.myPid());
        }

        Log.d("Boot: ", "Deactivate some stuff at boot time in order to prevent crashes");
        Preferences.setBrowserEnabled(context, false);
        Preferences.setOrwallEnabled(context, true);

        // initialize main chains
        initIPv6();
        initOutputs(app_uid);
        initInput(app_uid);

        // get lan subnet
        LANPolicy();


        if (Preferences.isSIPEnabled(this.context)) {
            app_uid = Long.valueOf(Preferences.getSIPApp(this.context));
            if (app_uid != 0) {
                Log.d("Boot", "Authorizing SIP");
                manageSip(true, app_uid);
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
     * - flush all rules we have in filter and nat tables
     * - put back default accounting rules in INPUT and OUTPUT
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
                // remove accounting_OUT chain
                "-F accounting_OUT",
                "-X accounting_OUT",
                // add back default system accounting
                "-A OUTPUT -j bw_OUTPUT",
                // set INPUT policy back to ACCEPT
                "-P INPUT ACCEPT",
                // flush all INPUT rules
                "-D INPUT -j ow_INPUT",
                "-F ow_INPUT",
                "-X ow_INPUT",
                // remove accounting_IN chain
                "-F accounting_IN",
                "-X accounting_IN",
                // add back default system accounting
                "-A INPUT -j bw_INPUT",
                // flush nat OUTPUT
                "-t nat -D OUTPUT -j ow_OUTPUT",
                "-t nat -F ow_OUTPUT",
                "-t nat -X ow_OUTPUT",
                // flush LAN
                "-F ow_LAN",
                "-X ow_LAN"
        };
        for (String rule : rules) {
            if (!iptRules.genericRule(rule)) {
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
            if (!iptRules.genericRuleV6(rule)) {
                Log.e("deactivate", "Unable to remove IPv6 rule");
                Log.e("deactivate", rule);
            }
        }
    }

    /**
     * Checks if iptables binary is on the device.
     * @return true if it finds iptables
     */
    public boolean iptablesExists() {
        File iptables = new File(Constants.IPTABLES);
        return iptables.exists();
    }

    public boolean ip6tablesExists() {
        File iptables = new File(Constants.IP6TABLES);
        return iptables.exists();
    }

    /**
     * Checks if current kernel supports comments for iptables.
     * Saves state in a sharedPreference.
     */
    public void supportComments() {
        String check = "-C INPUT -m comment --comment \"This is a witness comment\"";
        String rule = "-A INPUT -m comment --comment \"This is a witness comment\"";
        boolean support = (iptRules.genericRule(check) || iptRules.genericRule(rule));

        if (support) {
            Log.d("IPTables: ", "Comments are supported");
        } else {
            Log.d("IPTables: ", "Comments are NOT supported");
        }

        Preferences.setSupportComments(context, support);
        this.supportComment = support;
    }

    /**
     * Checks if iptables was successfully initialized by the init-script.
     * @return true if it finds the witness chain.
     */
    public boolean isInitialized() {
        String rule = "-C ow_OUTPUT_LOCK -j REJECT";
        return iptRules.genericRule(rule);
    }

    public boolean isOrwallReallyEnabled() {
        String rule = "-C OUTPUT -j ow_OUTPUT";
        return iptRules.genericRule(rule);
    }

    /**
     * update rules for LAN access.
     */
    public void LANPolicy() {
        NetworkHelper nwHelper = new NetworkHelper();
        String subnet = nwHelper.getSubnet(this.context);

        // Get subnet from SharedPreferences
        String old_subnet = Preferences.getCurrentSubnet(context);

        if (old_subnet != null && !old_subnet.equals(subnet)) {
            // Remove rules if we got another subnet in sharedPref
            iptRules.LanNoNat(old_subnet, false);
            Preferences.setCurrentSubnet(context, null);
        }

        if (subnet != null && !subnet.equals(old_subnet)) {
            // Do what's needed with current subnet
            iptRules.LanNoNat(subnet, true);

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
            if (!iptRules.genericRule(String.format(rule, action))) {
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
            if (!iptRules.genericRule(String.format(rule, action))) {
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
        if (iptRules.genericRuleV6("-C INPUT -j REJECT")) return;

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
            if (!iptRules.genericRuleV6(rule)) {
                Log.e(InitializeIptables.class.getName(), "Unable to initialize IPv6");
                Log.e(InitializeIptables.class.getName(), rule);
            }
        }
    }

    /**
     * Initialize OUTPUT chain in order to allow orbot network to go out
     * @param orbot_uid long UID for orbot application
     */
    public void initOutputs(final long orbot_uid) {
        String[] rules = {
                "-P OUTPUT DROP",
                "-D OUTPUT -j bw_OUTPUT",
                "-N ow_OUTPUT",
                "-A OUTPUT -j ow_OUTPUT",
                "-N accounting_OUT",
                "-A accounting_OUT -j bw_OUTPUT",
                "-A accounting_OUT -j ACCEPT",
                String.format(
                        "-A ow_OUTPUT -m owner --uid-owner %d -p tcp --dport 9030 -j accounting_OUT%s",
                        orbot_uid, (this.supportComment ? " -m comment --comment \"Forward Directory traffic to accounting\"" : "")
                ),
                String.format(
                        "-A ow_OUTPUT -m owner --uid-owner %d -m conntrack --ctstate NEW,RELATED,ESTABLISHED -j ACCEPT%s",
                        orbot_uid, (this.supportComment ? " -m comment --comment \"Allow Orbot outputs\"" : "")
                ),
                String.format(
                        "-A ow_OUTPUT -m owner --uid-owner 0 -d 127.0.0.1/32 -m conntrack --ctstate NEW,RELATED,ESTABLISHED -p udp -m udp --dport %d -j ACCEPT%s",
                        this.dns_proxy, (this.supportComment ? " -m comment --comment \"Allow DNS queries\"" : "")
                ),
                "-t nat -N ow_OUTPUT",
                "-t nat -A OUTPUT -j ow_OUTPUT",
                String.format(
                        "-t nat -A ow_OUTPUT -m owner --uid-owner 0 -p udp -m udp --dport 53 -j REDIRECT --to-ports %d%s",
                        this.dns_proxy, (this.supportComment ? " -m comment --comment \"Allow DNS queries\"" : "")
                ),
                // NAT
                String.format(
                        "-t nat -I ow_OUTPUT 1 -m owner --uid-owner %d -j RETURN%s",
                        orbot_uid, (this.supportComment ? " -m comment --comment \"Orbot bypasses itself.\"" : "")
                ),
                // LAN
                "-N ow_LAN",
                "-D OUTPUT -g ow_OUTPUT_LOCK"
        };
        for (String rule : rules) {
            if (!iptRules.genericRule(rule)) {
                Log.e(InitializeIptables.class.getName(), "Unable to initialize");
                Log.e(InitializeIptables.class.getName(), rule);
            }
        }
    }

    /**
     * Initialize INPUT chain
     * @param orbot_uid long UID for orbot application
     */
    public void initInput(final long orbot_uid) {
        String[] rules = {
                "-P INPUT DROP",
                "-D INPUT -j bw_INPUT",
                "-N ow_INPUT",
                "-A INPUT -j ow_INPUT",
                "-N accounting_IN",
                "-A accounting_IN -j bw_INPUT",
                "-A accounting_IN -j ACCEPT",
                String.format(
                        "-A ow_INPUT -m owner --uid-owner %d -m conntrack --ctstate NEW,RELATED,ESTABLISHED -j ACCEPT%s",
                        orbot_uid, (this.supportComment ? " -m comment --comment \"Allow Orbot inputs\"" : "")
                ),
                String.format(
                        "-A ow_INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT%s",
                        (this.supportComment ? " -m comment --comment \"Allow related,established inputs\"" : "")
                ),
                "-D INPUT -g ow_INPUT_LOCK"

        };
        for (String rule : rules) {
            if (!iptRules.genericRule(rule)) {
                Log.e(InitializeIptables.class.getName(), "Unable to initialize");
                Log.e(InitializeIptables.class.getName(), rule);
            }
        }
    }

    /**
     * Check if init-script is supported by the user device OS
     * It also save this state for later reference if needed
     * @return true if init is supported.
     */
    public boolean initSupported() {
        File dstDir = new File(dir_dst);
        return dstDir.exists();
    }

    /**
     * Checks some system settings before calling the method installing for good the init-script
     */
    public void installInitScript() {

        final String src_file = new File(context.getDir("bin", 0), "userinit.sh").getAbsolutePath();

        CheckSum check_src = new CheckSum(src_file);
        CheckSum check_dst = new CheckSum(dst_file);

        if (initSupported()) {

            if (!check_dst.hash().equals(check_src.hash())) {
                doInstallScripts(src_file, dst_file);
            }
            File local_dst = new File(dir_dst1);
            if (local_dst.exists()) {
                CheckSum check_dst1 = new CheckSum(dst_file1);
                if (!check_dst1.hash().equals(check_src.hash())) {
                    doInstallScripts(src_file, dst_file1);
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
    private void doInstallScripts(String src_file, String dst_file) {
        Shell shell = null;
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
    public void removeIniScript() {
        Shell shell = null;
        try {
            shell = Shell.startRootShell();
        } catch (IOException e) {
            Log.e("Shell", "Unable to get shell");
            return;
        }
        if (shell != null) {
            SimpleCommand command1 = new SimpleCommand("mount -o remount,rw /system");
            SimpleCommand command2 = new SimpleCommand("rm -f " + dst_file);
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
                "-%c ow_INPUT -m owner --uid-owner %d -m conntrack --ctstate RELATED,ESTABLISHED -p udp -j accounting_IN",
                "-%c ow_OUTPUT -m owner --uid-owner %d -p udp -j accounting_OUT",
                "-t nat -%c ow_OUTPUT -m owner --uid-owner %d -p udp -j RETURN",
        };
        char action = (status ? 'A' : 'D');

        for (String rule : rules) {
            iptRules.genericRule(String.format(rule, action, uid));
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
        };
        char action = (status ? 'I' : 'D');

        for (String rule : rules) {
            Log.d("ManageCaptiveBrowser", String.format(rule, action, uid));
            iptRules.genericRule(String.format(rule, action, uid));
        }
        // As android now uses kernel resolver for DNS, we have to allow dns to be freed…
        // This is described in issue #60 and was spotted by Mike Perry, from Tor Project.
        String rule;
        if (status) {
            // we enable browser, hence we remove the DNS redirection
            rule = "-t nat -I OUTPUT -m owner --uid-owner 0 -p udp -m udp --dport 53 -j RETURN";
        } else {
            // we disable browser, hence we put back DNS redirection.
            rule = "-t nat -D OUTPUT -m owner --uid-owner 0 -p udp -m udp --dport 53 -j RETURN";
        }
        iptRules.genericRule(rule);
    }

    public void getTetheredInterfaces(Context context, Set<String> set){
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for(Method method: cm.getClass().getDeclaredMethods()){
            if(method.getName().equals("getTetheredIfaces")){
                try {
                    String[] intfs = (String[]) method.invoke(cm);
                    if (intfs != null)
                        Collections.addAll(set, intfs);
                    break;
                } catch (Exception e) {
                   return;
                }
            }
        }
    }

    public void tetherUpdate(Context context, Set<String> before, Set<String> after){

        if (before != null) {
            for (String item: before){
                if (!after.contains(item))
                    enableDHCP(false, item);
            }
        }

        for (String item: after){
            if (before == null || !before.contains(item))
                enableDHCP(true, item);
        }

        Preferences.setTetherInterfaces(context, after);
    }

    public void enableDHCP(boolean status, String intf){

        char action = (status ? 'A' : 'D');
        ArrayList<String> rules = new ArrayList<>();

        rules.add(
                String.format(
                        "-%c ow_INPUT -i %s -p udp -m udp --dport 67 -j ACCEPT%s",
                        action, intf, (this.supportComment ? " -m comment --comment \"Allow DHCP tethering\"" : "")
        ));

        rules.add(
                String.format(
                        "-%c ow_OUTPUT -o %s -p udp -m udp --sport 67 -j ACCEPT%s",
                        action, intf, (this.supportComment ? " -m comment --comment \"Allow DHCP tethering\"" : "")

        ));
        for (String rule : rules) {
            if (!iptRules.genericRule(rule)) {
                Log.e("Tethering", "Unable to apply rule");
                Log.e("Tethering", rule);
            }
        }
    }

/* could be usefull later
    public String getMask(String intf){
        try {
            NetworkInterface ntwrk = NetworkInterface.getByName(intf);
            Iterator<InterfaceAddress> addrList = ntwrk.getInterfaceAddresses().iterator();
            while (addrList.hasNext()) {
                InterfaceAddress addr = addrList.next();
                InetAddress ip = addr.getAddress();
                if (ip instanceof Inet4Address) {
                    String mask = ip.getHostAddress() + "/" +
                            addr.getNetworkPrefixLength();
                    return mask;
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
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
            if (!iptRules.genericRule(rule)) {
                Log.e("Tor tethering", "Unable to apply rule");
                Log.e("Tor tethering", rule);
            }
        }
    }
*/

    /**
     * Apply or remove rules for captive portal detection.
     * Captive portal detection works with DNS and redirection detection.
     * Once the device is connected, Android will probe the network in order to get a page, located on Google servers.
     * If it can connect to it, this means we're not in a captive network; otherwise, it will prompt for network login.
     * @param status boolean, true if we want to enable this probe.
     * @param context application context
     */
    public void enableCaptiveDetection(boolean status, Context context) {
        // TODO: find a way to disable it on android <4.4
        // TODO: we may want to get some setting writer directly through the API.
        // This seems to be done with a System app only. orWall may become a system app.
        if (Build.VERSION.SDK_INT > 18) {

            String CMD;
            if (status) {
                CMD = new File(context.getDir("bin", 0), "activate_portal.sh").getAbsolutePath();
            } else {
                CMD = new File(context.getDir("bin", 0), "deactivate_portal.sh").getAbsolutePath();
            }
            Shell shell = null;
            try {
                shell = Shell.startRootShell();
            } catch (IOException e) {
                Log.e("Shell", "Unable to get shell");
            }

            if (shell != null) {
                SimpleCommand command = new SimpleCommand(CMD);
                try {
                    shell.add(command).waitForFinish();
                } catch (IOException e) {
                    Log.e("Shell", "IO Error");
                } catch (TimeoutException e) {
                    Log.e("Shell", "Timeout");
                } finally {
                    try {
                        shell.close();
                    } catch (IOException e) {

                    }
                }
            }

        }
    }
}
