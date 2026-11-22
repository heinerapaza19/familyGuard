package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Etapa 3: selección múltiple, filtros y paginación del panel «Mis dispositivos». */
class DevicePanelSelectionTests {
    private static final long LUZ = 1L, MATEO = 2L;

    private static AdminApiClient.DevicePanelInfo device(long id, String name, Long childId, String child,
                                                         boolean connected, String status, String sync,
                                                         int appPending, int webErrors) {
        AdminApiClient.DeviceInfo info = new AdminApiClient.DeviceInfo(id, name, "COMPUTADORA", "Windows", "11",
                childId, child, status, Instant.EPOCH, null, null, connected, "usuario", "1.0",
                connected ? Instant.now() : null, "", 3, "APLICADA".equals(sync) ? 3 : 2, sync);
        return new AdminApiClient.DevicePanelInfo(info, appPending, 0, appPending, 0,
                appPending > 0 ? "PENDIENTE" : "SIN_RESTRICCIONES", webErrors, 0, 0, webErrors,
                webErrors > 0 ? "ERROR" : "SIN_REGLAS");
    }

    /** Escenario: laptop de Luz en Cusco, PC de Luz en Juliaca, equipos de Mateo y un aula de 10+ laptops. */
    private static List<AdminApiClient.DevicePanelInfo> family() {
        List<AdminApiClient.DevicePanelInfo> list = new ArrayList<>();
        list.add(device(1, "Laptop Luz Cusco", LUZ, "Luz", true, "AUTORIZADO", "APLICADA", 0, 0));
        list.add(device(2, "PC Luz Juliaca", LUZ, "Luz", false, "AUTORIZADO", "PENDIENTE", 2, 0));
        list.add(device(3, "PC Mateo", MATEO, "Mateo", true, "AUTORIZADO", "APLICADA", 0, 1));
        list.add(device(4, "Laptop revocada", MATEO, "Mateo", false, "REVOCADO", "PENDIENTE", 0, 0));
        for (long i = 10; i < 22; i++) list.add(device(i, "Laptop aula " + i, MATEO, "Mateo", i % 2 == 0, "AUTORIZADO", "APLICADA", 0, 0));
        return list;
    }

    @Test void seleccionIndividualYContador() {
        DeviceSelection selection = new DeviceSelection();
        selection.retainKnown(family());
        selection.set(1, true); selection.set(3, true); selection.toggle(3);
        assertTrue(selection.isSelected(1)); assertFalse(selection.isSelected(3)); assertEquals(1, selection.count());
    }

    @Test void noSeleccionaIdentificadoresAjenosALaFamilia() {
        DeviceSelection selection = new DeviceSelection();
        selection.retainKnown(family());
        selection.set(999, true);
        assertEquals(0, selection.count());
    }

    @Test void seleccionarTodosIncluyeTodasLasPaginasDelFiltroYDeseleccionarLimpia() {
        List<AdminApiClient.DevicePanelInfo> all = family();
        DeviceSelection selection = new DeviceSelection();
        selection.retainKnown(all);
        List<AdminApiClient.DevicePanelInfo> mateo = new DeviceFilter("", MATEO, "Todos", "Todas", "Todas").apply(all);
        PaginationModel<AdminApiClient.DevicePanelInfo> pages = new PaginationModel<>();
        pages.setItems(mateo);
        assertEquals(14, pages.total()); assertEquals(10, pages.page().size());
        assertEquals(14, selection.selectAll(mateo));
        assertEquals(0, selection.hiddenCount(mateo));
        assertEquals(14, selection.hiddenCount(pages.page()) + pages.page().size());
        selection.clear();
        assertEquals(0, selection.count());
    }

    @Test void seleccionarEquiposDeUnHijoSoloAutorizadosAunqueEstenFiltrados() {
        List<AdminApiClient.DevicePanelInfo> all = family();
        DeviceSelection selection = new DeviceSelection();
        selection.retainKnown(all);
        assertEquals(2, selection.selectChild(all, LUZ));
        assertEquals(Set.of(1L, 2L), selection.ids());
        selection.clear();
        assertEquals(13, selection.selectChild(all, MATEO));
        assertFalse(selection.isSelected(4), "un equipo revocado no se selecciona en bloque");
    }

    @Test void seleccionarTodaLaFamiliaExcluyeRevocadosYSeConservaAlCambiarFiltros() {
        List<AdminApiClient.DevicePanelInfo> all = family();
        DeviceSelection selection = new DeviceSelection();
        selection.retainKnown(all);
        assertEquals(15, selection.selectFamily(all));
        List<AdminApiClient.DevicePanelInfo> visible = new DeviceFilter("luz", null, "Todos", "Todas", "Todas").apply(all);
        assertEquals(2, visible.size());
        assertEquals(13, selection.hiddenCount(visible));
        assertEquals(15, selection.count());
    }

    @Test void refrescoDescartaEquiposQueYaNoPertenecenALaFamilia() {
        List<AdminApiClient.DevicePanelInfo> all = family();
        DeviceSelection selection = new DeviceSelection();
        selection.retainKnown(all);
        selection.selectFamily(all);
        selection.retainKnown(all.subList(0, 3));
        assertEquals(Set.of(1L, 2L, 3L), selection.ids());
    }

    @Test void filtrosPorTextoHijoConexionSincronizacionYRestricciones() {
        List<AdminApiClient.DevicePanelInfo> all = family();
        assertEquals(List.of(1L, 2L), ids(new DeviceFilter("LUZ", null, "Todos", "Todas", "Todas").apply(all)));
        assertEquals(List.of(2L), ids(new DeviceFilter("juliaca", null, "Todos", "Todas", "Todas").apply(all)));
        assertTrue(new DeviceFilter("", null, "Conectados", "Todas", "Todas").apply(all).stream().allMatch(d -> d.device().connected()));
        assertTrue(new DeviceFilter("", null, "Desconectados", "Todas", "Todas").apply(all).stream().noneMatch(d -> d.device().connected()));
        assertEquals(List.of(2L, 4L), ids(new DeviceFilter("", null, "Todos", "Pendiente", "Todas").apply(all)));
        assertEquals(List.of(2L), ids(new DeviceFilter("", null, "Todos", "Todas", "Con pendientes").apply(all)));
        assertEquals(List.of(3L), ids(new DeviceFilter("", null, "Todos", "Todas", "Con errores").apply(all)));
        assertEquals(all.size(), DeviceFilter.none().apply(all).size());
    }

    @Test void etiquetasDistinguenEstados() {
        List<AdminApiClient.DevicePanelInfo> all = family();
        assertEquals("Pendientes (2)", DeviceFilter.appLabel(all.get(1)));
        assertEquals("Error (1)", DeviceFilter.webLabel(all.get(2)));
        assertEquals("Sin restricciones", DeviceFilter.appLabel(all.get(0)));
        assertEquals("Sin reglas", DeviceFilter.webLabel(all.get(0)));
    }

    @Test void refrescoAutomaticoConservaLaPaginaActual() {
        PaginationModel<Integer> model = new PaginationModel<>();
        model.setItems(java.util.stream.IntStream.rangeClosed(1, 30).boxed().toList());
        model.next(); model.next();
        model.replaceItems(java.util.stream.IntStream.rangeClosed(1, 30).boxed().toList());
        assertEquals(2, model.pageIndex());
        model.replaceItems(java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList());
        assertEquals(1, model.pageIndex(), "si la página desaparece se muestra la última existente");
    }

    private static List<Long> ids(List<AdminApiClient.DevicePanelInfo> values) {
        return values.stream().map(AdminApiClient.DevicePanelInfo::id).toList();
    }
}
