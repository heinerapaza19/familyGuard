package com.familyguard.familyguard.api;

import static com.familyguard.familyguard.api.dto.RestriccionDtos.*;
import com.familyguard.familyguard.service.RestriccionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RestriccionController {
    private final RestriccionService service;
    public RestriccionController(RestriccionService service){this.service=service;}

    @GetMapping("/restrictions")
    public List<RestriccionResponse> listar(Authentication auth,
            @RequestParam(required=false) Long menorId,@RequestParam(required=false) Long dispositivoId){
        return service.listar(AuthController.principal(auth),menorId,dispositivoId);
    }
    @PostMapping("/restrictions")
    public ResponseEntity<RestriccionResponse> crear(Authentication auth,@Valid @RequestBody RestriccionRequest request){
        return ResponseEntity.status(HttpStatus.CREATED).body(service.guardar(AuthController.principal(auth),null,request));
    }
    @PutMapping("/restrictions/{id}")
    public RestriccionResponse modificar(Authentication auth,@PathVariable Long id,@Valid @RequestBody RestriccionRequest request){
        return service.guardar(AuthController.principal(auth),id,request);
    }
    @DeleteMapping("/restrictions/{id}")
    public ResponseEntity<Void> desactivar(Authentication auth,@PathVariable Long id){
        service.desactivar(AuthController.principal(auth),id); return ResponseEntity.noContent().build();
    }
    @PatchMapping("/restrictions/{id}/active")
    public RestriccionResponse cambiarEstado(Authentication auth,@PathVariable Long id,@RequestParam boolean value){
        return service.cambiarEstado(AuthController.principal(auth),id,value);
    }
    @GetMapping("/agent/rules")
    public ReglasAgenteResponse reglas(@RequestHeader("X-Agent-Token") String token){return service.reglas(token);}
    @PostMapping("/agent/restriction-events")
    public EventosRestriccionResponse eventos(@RequestHeader("X-Agent-Token") String token,
            @Valid @RequestBody EventosRestriccionRequest request){return service.registrarEventos(token,request);}
}
