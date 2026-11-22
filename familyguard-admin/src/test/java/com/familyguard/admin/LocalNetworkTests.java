package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Red actual de la PC del padre: Wi-Fi primero, sin adaptadores virtuales, nombre de la red desde netsh. */
class LocalNetworkTests {
    private static final LocalNetwork.Adapter WIFI = new LocalNetwork.Adapter("wlan0", "Intel(R) Wi-Fi 6 AX201 160MHz", "192.168.1.20");
    private static final LocalNetwork.Adapter CABLE = new LocalNetwork.Adapter("eth3", "Realtek PCIe GbE Family Controller", "10.0.0.15");
    private static final LocalNetwork.Adapter VBOX = new LocalNetwork.Adapter("eth7", "VirtualBox Host-Only Ethernet Adapter", "192.168.56.1");
    private static final LocalNetwork.Adapter HYPERV = new LocalNetwork.Adapter("eth9", "Hyper-V Virtual Ethernet Adapter", "172.20.0.1");

    @Test
    void eligeLaWifiYSuNombre() {
        var net = LocalNetwork.choose(List.of(VBOX, CABLE, WIFI, HYPERV), "CUKIER WIFI");
        assertEquals("Wi-Fi", net.type());
        assertEquals("CUKIER WIFI", net.networkName());
        assertEquals("192.168.1.20", net.ipv4());
    }

    @Test
    void sinWifiUsaElCableYNuncaUnAdaptadorVirtual() {
        var cable = LocalNetwork.choose(List.of(VBOX, CABLE, HYPERV), null);
        assertEquals("Ethernet", cable.type());
        assertEquals("10.0.0.15", cable.ipv4());
        assertNull(cable.networkName());
        var none = LocalNetwork.choose(List.of(VBOX, HYPERV), null);
        assertEquals("Sin conexión de red", none.type());
        assertFalse(none.connected());
    }

    @Test
    void leeElNombreDeLaRedDeNetsh() {
        String netsh = """
                Hay 1 interfaz en el sistema:

                    Nombre                 : Wi-Fi
                    Descripción            : Intel(R) Wi-Fi 6 AX201 160MHz
                    Estado                 : conectado
                    SSID                   : CUKIER WIFI
                    AP BSSID               : aa:bb:cc:dd:ee:ff
                """;
        assertEquals("CUKIER WIFI", LocalNetwork.wifiName(netsh));
        assertNull(LocalNetwork.wifiName("    Estado                 : desconectado\\n"));
        assertNull(LocalNetwork.wifiName(null));
    }
}
