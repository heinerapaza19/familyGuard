package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PaginationModelTests {
    @Test void respetaLimitesPrimeraYUltimaPagina() {
        PaginationModel<Integer> model=new PaginationModel<>();
        model.setItems(IntStream.rangeClosed(1,64).boxed().toList());
        assertEquals(10,model.page().size());assertFalse(model.hasPrevious());assertTrue(model.hasNext());
        model.last();assertEquals(7,model.pageCount());assertEquals(61,model.fromDisplay());assertEquals(64,model.toDisplay());
        assertEquals(4,model.page().size());assertFalse(model.hasNext());model.next();assertEquals(6,model.pageIndex());
        model.first();model.previous();assertEquals(0,model.pageIndex());
    }
    @Test void cambioDeTamanoYFiltrosReiniciaPrimeraPagina() {
        PaginationModel<Integer> model=new PaginationModel<>();model.setItems(IntStream.range(0,80).boxed().toList());
        model.next();model.setPageSize(25);assertEquals(0,model.pageIndex());assertEquals(25,model.page().size());
        model.next();model.setItems(IntStream.range(0,3).boxed().toList());assertEquals(0,model.pageIndex());assertEquals(3,model.total());
        assertThrows(IllegalArgumentException.class,()->model.setPageSize(12));
    }
    @Test void ordenamientoSeConservaEntrePaginas() {
        PaginationModel<Integer> model=new PaginationModel<>();model.setItems(IntStream.rangeClosed(1,30).boxed().toList());
        model.sort(java.util.Comparator.reverseOrder());assertEquals(30,model.page().get(0));
        model.next();assertEquals(20,model.page().get(0));model.setPageSize(25);assertEquals(30,model.page().get(0));
    }
}
