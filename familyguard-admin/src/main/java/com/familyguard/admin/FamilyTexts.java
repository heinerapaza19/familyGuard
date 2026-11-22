package com.familyguard.admin;

import java.util.List;

/**
 * Textos de los estados vacíos del Administrador. El padre navega libremente aunque no tenga hijos: solo se
 * muestran sugerencias discretas; nunca se abre ni se obliga a completar el registro de un hijo.
 */
final class FamilyTexts {
    static final String NO_CHILDREN = "Todavía no has agregado hijos.";
    static final String ADD_CHILD = "Agregar hijo";
    static final String CHILDREN_HINT = "Cuando quieras, agrégalos en «Mis hijos» con el botón «Agregar hijo».";
    static final String NO_DEVICES = "Aún no hay computadoras vinculadas.";
    static final String DEVICES_HINT = "En «Mis dispositivos», pulsa «Agregar dispositivo» y elige a uno de tus hijos.";
    static final String NO_MATCHING_CHILDREN = "Ningún hijo coincide con la búsqueda.";

    private FamilyTexts() { }

    /** Indicación del diálogo «Agregar dispositivo» cuando no hay hijos activos para elegir; {@code null} si los hay. */
    static String linkWithoutChildren(List<AdminApiClient.ChildProfile> children) {
        if (children.stream().anyMatch(AdminApiClient.ChildProfile::activo)) return null;
        if (children.isEmpty()) return NO_CHILDREN + " Agrégalos en «Mis hijos» y vuelve aquí para vincular la computadora.";
        return "Tus hijos registrados están desactivados. Reactiva uno en «Mis hijos» para vincular una computadora.";
    }
}
