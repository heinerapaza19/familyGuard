package com.familyguard.admin;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Selección de dispositivos independiente de la página visible, los filtros y JavaFX.
 * Solo puede contener equipos que el backend devolvió para la familia de la sesión:
 * {@link #retainKnown} descarta cualquier identificador que ya no pertenezca a ese listado.
 */
final class DeviceSelection {
    private final Set<Long> selected = new LinkedHashSet<>();
    private Set<Long> known = Set.of();

    /** Equipos conocidos de la familia (respuesta completa del backend, sin filtros). */
    void retainKnown(Collection<AdminApiClient.DevicePanelInfo> familyDevices) {
        Set<Long> ids = new LinkedHashSet<>();
        familyDevices.forEach(d -> ids.add(d.id()));
        known = Set.copyOf(ids);
        selected.retainAll(known);
    }

    boolean isSelected(long id) { return selected.contains(id); }
    int count() { return selected.size(); }
    Set<Long> ids() { return Set.copyOf(selected); }

    void set(long id, boolean value) {
        if (!value) selected.remove(id);
        else if (known.contains(id)) selected.add(id);
    }
    void toggle(long id) { set(id, !isSelected(id)); }

    /** «Seleccionar todos»: los equipos que cumplen los filtros actuales, en todas las páginas. */
    int selectAll(List<AdminApiClient.DevicePanelInfo> filtered) { return add(filtered, d -> true); }

    /** Todos los equipos autorizados de un hijo, aunque los filtros los oculten. */
    int selectChild(List<AdminApiClient.DevicePanelInfo> familyDevices, long childId) {
        return add(familyDevices, d -> d.authorized() && Objects.equals(d.device().childId(), childId));
    }

    /** Todos los equipos autorizados de la familia (requiere confirmar el alcance en la interfaz). */
    int selectFamily(List<AdminApiClient.DevicePanelInfo> familyDevices) {
        return add(familyDevices, AdminApiClient.DevicePanelInfo::authorized);
    }

    void clear() { selected.clear(); }

    /** Seleccionados que hoy no son visibles por filtros o paginación. */
    int hiddenCount(List<AdminApiClient.DevicePanelInfo> visible) {
        Set<Long> shown = new LinkedHashSet<>();
        visible.forEach(d -> shown.add(d.id()));
        return (int) selected.stream().filter(id -> !shown.contains(id)).count();
    }

    private int add(List<AdminApiClient.DevicePanelInfo> devices, Predicate<AdminApiClient.DevicePanelInfo> condition) {
        int before = selected.size();
        devices.stream().filter(condition).filter(d -> known.contains(d.id())).forEach(d -> selected.add(d.id()));
        return selected.size() - before;
    }
}
