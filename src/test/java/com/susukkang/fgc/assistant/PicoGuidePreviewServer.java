package com.susukkang.fgc.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.susukkang.fgc.ai.config.LlmProperties;
import com.susukkang.fgc.ai.repository.AiCallLogRepository;
import com.susukkang.fgc.ai.service.LlmGateway;
import com.susukkang.fgc.assistant.dto.PicoAskRequest;
import com.susukkang.fgc.assistant.service.*;
import com.susukkang.fgc.common.web.ApiResponse;
import jakarta.validation.Validation;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Local QA fixture only. No real key, database, production authentication or financial handlers. */
public final class PicoGuidePreviewServer {
    public static void main(String[] args) throws Exception {
        int port=args.length==0?4192:Integer.parseInt(args[0]);
        var mapper=new ObjectMapper().findAndRegisterModules();
        var transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // Explicitly empty key: even if the developer has an environment key, it is never consumed.
        var properties=new LlmProperties("","https://api.openai.com/v1","gpt-4o-mini",Duration.ofSeconds(5),20000,512,Duration.ofMinutes(10));
        var gateway=new LlmGateway(properties,mock(AiCallLogRepository.class),transactions);
        var context=mock(com.susukkang.fgc.assistant.service.PicoPaymentContextService.class);
        when(context.read(org.mockito.ArgumentMatchers.anyLong())).thenThrow(new com.susukkang.fgc.common.exception.FgcBusinessException(com.susukkang.fgc.common.exception.FgcErrorCode.COMMON_004));
        var service=new PicoAskService(gateway,new PicoGuideCatalog(mapper),mapper,context,properties);
        var validation=Validation.buildDefaultValidatorFactory();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/api/v1/ai/pico/ask",exchange->{
            byte[] output; int status=200;
            try {
                if(!"POST".equals(exchange.getRequestMethod()) || !exchange.getRequestURI().getPath().equals("/api/v1/ai/pico/ask")) throw new IllegalArgumentException();
                byte[] body=exchange.getRequestBody().readNBytes(65537); if(body.length>65536) throw new IllegalArgumentException();
                var request=mapper.readValue(body,PicoAskRequest.class);
                if(!validation.getValidator().validate(request).isEmpty()) throw new IllegalArgumentException();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("synthetic-preview","unused",List.of(new SimpleGrantedAuthority("ROLE_SETTLEMENT"))));
                output=mapper.writeValueAsBytes(ApiResponse.success(service.ask(request)));
            } catch(Exception error) { status=400; output="{\"data\":null,\"error\":{\"code\":\"PREVIEW_INVALID_REQUEST\"},\"requestId\":\"local-qa\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8); }
            finally { SecurityContextHolder.clearContext(); }
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.sendResponseHeaders(status,output.length); exchange.getResponseBody().write(output); exchange.close();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);validation.close();}));
        server.start(); System.out.println("Pico fixed-guide QA server on 127.0.0.1:"+port+"; external key disabled, no database or financial writes.");
    }
}
