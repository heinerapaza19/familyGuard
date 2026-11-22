package com.familyguard.familyguard.api;
import static com.familyguard.familyguard.api.dto.SitioDtos.*;import com.familyguard.familyguard.service.SitioService;import jakarta.validation.Valid;import java.util.*;import org.springframework.http.*;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api") public class SitioController{private final SitioService service;public SitioController(SitioService s){service=s;}
 @GetMapping("/web-restrictions")List<Response> list(Authentication a,@RequestParam(required=false)Long menorId,@RequestParam(required=false)String q){return service.list(AuthController.principal(a),menorId,q);}
 @PostMapping("/web-restrictions")ResponseEntity<Response> create(Authentication a,@Valid @RequestBody Request r){return ResponseEntity.status(201).body(service.save(AuthController.principal(a),null,r));}
 @PutMapping("/web-restrictions/{id}")Response update(Authentication a,@PathVariable Long id,@Valid @RequestBody Request r){return service.save(AuthController.principal(a),id,r);}
 @DeleteMapping("/web-restrictions/{id}")ResponseEntity<Void> delete(Authentication a,@PathVariable Long id){service.deactivate(AuthController.principal(a),id);return ResponseEntity.noContent().build();}
 @PatchMapping("/web-restrictions/{id}/active")Response toggle(Authentication a,@PathVariable Long id,@RequestParam boolean value){return service.toggle(AuthController.principal(a),id,value);}
 @GetMapping("/web-categories")List<Category> categories(Authentication a){return service.categories(AuthController.principal(a));}
 @GetMapping("/web-restrictions/{id}/events")List<EventResponse> ruleEvents(Authentication a,@PathVariable Long id){return service.ruleEvents(AuthController.principal(a),id);}
 @GetMapping("/web-events")List<EventResponse> events(Authentication a){return service.eventList(AuthController.principal(a));}
 @DeleteMapping("/web-events")ResponseEntity<Void> clear(Authentication a){service.clearEvents(AuthController.principal(a));return ResponseEntity.noContent().build();}
 @GetMapping("/agent/web-rules")AgentResponse rules(@RequestHeader("X-Agent-Token")String token){return service.agentRules(token);}
 @PostMapping("/agent/web-events")EventsResponse events(@RequestHeader("X-Agent-Token")String token,@Valid @RequestBody EventsRequest r){return service.events(token,r);}
}
