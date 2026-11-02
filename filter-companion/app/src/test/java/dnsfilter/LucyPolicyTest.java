package dnsfilter;

import org.junit.Test;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import static org.junit.Assert.*;

public class LucyPolicyTest {
    @Test public void customRuleIncludesSubdomainsWithoutSubstringOverblocking() {
        BlockedHosts hosts = new BlockedHosts(10, 10, 10);
        hosts.finalPrepare(10);
        hosts.addOverrule("blocked.lucy.test", true);
        assertTrue(hosts.contains("blocked.lucy.test"));
        assertTrue(hosts.contains("sub.blocked.lucy.test"));
        assertTrue(hosts.contains("nested.sub.blocked.lucy.test"));
        assertFalse(hosts.contains("notblocked.lucy.test"));
        assertFalse(hosts.contains("blocked.lucy.test.example.com"));
        assertFalse(hosts.contains("example.com"));
    }
    @Test public void bundledFeedIsSubstantialAndPolicySelectsOnlyNsfw() throws Exception {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream("src/main/assets/lucy.conf")) { props.load(in); }
        assertEquals("false", props.getProperty("detectDNS"));
        assertEquals("true", props.getProperty("AUTOSTART"));
        assertEquals("true", props.getProperty("filterActive"));
        assertEquals("FILTERHOSTS.TXT", props.getProperty("filterHostsFile"));
        assertEquals("true", props.getProperty("filterAutoUpdateURL_switchs"));
        assertTrue(props.getProperty("filterAutoUpdateURL").endsWith("/wildcard/nsfw-onlydomains.txt"));
        long domains = Files.lines(Paths.get("src/main/assets/hagezi-nsfw.txt"))
            .filter(line -> !line.startsWith("#") && !line.trim().isEmpty()).count();
        assertTrue("Missing or invalid bundled list", domains > 50000);
        assertEquals("blocked.lucy.test", Files.readAllLines(Paths.get("src/main/assets/additionalHosts.txt")).get(1));
    }

    @Test public void upgradePolicyOverridesUnsafePreviousValues() {
        Properties previous = new Properties();
        previous.setProperty("fallbackDNS", "8.8.8.8");
        previous.setProperty("filterActive", "false");
        previous.setProperty("customSetting", "preserved");
        Properties required = new Properties();
        required.setProperty("fallbackDNS", "family.cloudflare-dns.com");
        required.setProperty("filterActive", "true");
        Properties merged = DNSFilterManager.mergeLucyPolicy(previous, required);
        assertEquals("family.cloudflare-dns.com", merged.getProperty("fallbackDNS"));
        assertEquals("true", merged.getProperty("filterActive"));
        assertEquals("preserved", merged.getProperty("customSetting"));
    }

    @Test public void upgradeAddsMandatoryHostsWithoutDeletingAdministratorRules() {
        String merged = DNSFilterManager.mergeMandatoryHosts("example.org\n", "# required\nblocked.lucy.test\n");
        assertTrue(merged.contains("example.org\n"));
        assertTrue(merged.contains("blocked.lucy.test\n"));
        assertEquals(merged.indexOf("blocked.lucy.test"), merged.lastIndexOf("blocked.lucy.test"));
    }
}
