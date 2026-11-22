package com.familyguard.admin;

import java.util.List;
import java.util.Comparator;

/** Estado de paginación independiente de JavaFX, reutilizable y comprobable. */
final class PaginationModel<T> {
    private List<T> items = List.of();
    private int pageSize = 10;
    private int pageIndex;

    private Comparator<T> comparator;
    void setItems(List<T> values) { items = values == null ? List.of() : sorted(values); pageIndex = 0; }
    /** Actualiza los datos (p. ej., refresco automático) conservando la página actual si aún existe. */
    void replaceItems(List<T> values) {
        items = values == null ? List.of() : sorted(values);
        pageIndex = Math.min(pageIndex, pageCount() - 1);
    }
    void sort(Comparator<T> value) { comparator=value;items=sorted(items);pageIndex=0; }
    void setPageSize(int value) {
        if (value != 10 && value != 25 && value != 50 && value != 100)
            throw new IllegalArgumentException("Tamaño de página no permitido");
        pageSize = value; pageIndex = 0;
    }
    int pageSize() { return pageSize; }
    int pageIndex() { return pageIndex; }
    int total() { return items.size(); }
    int pageCount() { return Math.max(1, (total() + pageSize - 1) / pageSize); }
    boolean hasPrevious() { return pageIndex > 0; }
    boolean hasNext() { return pageIndex + 1 < pageCount(); }
    void first() { pageIndex = 0; }
    void previous() { if (hasPrevious()) pageIndex--; }
    void next() { if (hasNext()) pageIndex++; }
    void last() { pageIndex = pageCount() - 1; }
    int fromDisplay() { return total() == 0 ? 0 : pageIndex * pageSize + 1; }
    int toDisplay() { return Math.min(total(), (pageIndex + 1) * pageSize); }
    List<T> page() {
        int from = Math.min(total(), pageIndex * pageSize);
        return items.subList(from, Math.min(total(), from + pageSize));
    }
    private List<T> sorted(List<T> values){
        if(comparator==null)return List.copyOf(values);
        return values.stream().sorted(comparator).toList();
    }
}
