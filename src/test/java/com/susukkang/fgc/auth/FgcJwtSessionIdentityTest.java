package com.susukkang.fgc.auth;
import com.susukkang.fgc.auth.service.*;
import com.susukkang.fgc.auth.repository.AuthRefreshTokenRepository;
import com.susukkang.fgc.auth.entity.AuthRefreshToken;
import com.susukkang.fgc.auth.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class FgcJwtSessionIdentityTest {
    @Test void preservesOnlyValidatedSessionIdentityAndUsesCurrentDatabaseRole() {
        var users=mock(FgcUserDetailsService.class);var sessions=mock(AuthRefreshTokenRepository.class);var session=mock(AuthRefreshToken.class);
        UUID sid=UUID.randomUUID();var view=new AppUserView();view.setUserId(1L);view.setLoginId("settler");view.setUserName("settler");view.setPasswordHash("{noop}test");view.setRoleCode("SETTLEMENT");
        when(users.loadUserByUsername("settler")).thenReturn(new FgcUserDetails(view,true,true));when(sessions.findFirstBySessionIdOrderByRefreshTokenIdDesc(sid)).thenReturn(Optional.of(session));when(session.getUserId()).thenReturn(1L);when(session.isActive()).thenReturn(true);
        var converter=new FgcJwtAuthenticationConverter(users,sessions);var jwt=Jwt.withTokenValue("not-retained").header("alg","none").subject("settler").claim("sid",sid.toString()).claim("roles",List.of("SYSTEM_ADMIN")).build();
        var auth=converter.convert(jwt);assertThat(auth.getDetails()).isEqualTo(new FgcJwtAuthenticationConverter.SessionIdentity(sid.toString()));assertThat(auth.getCredentials()).isNull();assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_SETTLEMENT");
        when(sessions.findFirstBySessionIdOrderByRefreshTokenIdDesc(sid)).thenReturn(Optional.empty());assertThatThrownBy(()->converter.convert(jwt)).isInstanceOf(com.susukkang.fgc.auth.service.JwtSessionRevokedException.class);
    }
}
