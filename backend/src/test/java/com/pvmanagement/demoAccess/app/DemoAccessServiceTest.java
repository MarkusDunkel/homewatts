package com.pvmanagement.demoAccess.app;

import com.pvmanagement.auth.app.AuthService;
import com.pvmanagement.auth.domain.AuthResult;
import com.pvmanagement.demoAccess.domain.DemoAccessException;
import com.pvmanagement.demoAccess.domain.DemoAccessProperties;
import com.pvmanagement.demoAccess.domain.DemoClaims;
import com.pvmanagement.demoAccess.domain.DemoKey;
import com.pvmanagement.demoAccess.domain.DemoRedemption;
import com.pvmanagement.demoAccess.domain.DemoTokenService;
import com.pvmanagement.demoAccess.infra.DemoKeyRepository;
import com.pvmanagement.demoAccess.infra.DemoRedemptionRepository;
import com.pvmanagement.identity.domain.Role;
import com.pvmanagement.identity.domain.RoleName;
import com.pvmanagement.identity.domain.UserAccount;
import com.pvmanagement.identity.infra.RoleRepository;
import com.pvmanagement.identity.infra.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DemoAccessServiceTest {

    @Mock
    private DemoTokenService tokenService;
    @Mock
    private DemoKeyRepository demoKeyRepository;
    @Mock
    private DemoRedemptionRepository redemptionRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthService authService;

    private DemoAccessService service;
    private DemoAccessProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DemoAccessProperties();
        properties.setDefaultMaxActivations(3);
        properties.setKeyValidDays(10);
        service = new DemoAccessService(tokenService,
                demoKeyRepository,
                redemptionRepository,
                userAccountRepository,
                roleRepository,
                passwordEncoder,
                authService,
                properties);
    }

    @Test
    void redeemRejectsInvalidScope() {
        DemoClaims claims = new DemoClaims("acme", "key-1", "other", null, null);
        when(tokenService.parseAndValidate("token")).thenReturn(claims);

        assertThatThrownBy(() -> service.redeem("token", "127.0.0.1", "agent"))
                .isInstanceOf(DemoAccessException.class)
                .hasMessage("Demo token has invalid scope");
    }

    @Test
    void redeemRejectsRevokedKey() {
        DemoKey demoKey = new DemoKey();
        demoKey.setKeyId("key-1");
        demoKey.setOrg("acme");
        demoKey.setRevoked(true);
        DemoClaims claims = new DemoClaims("acme", "key-1", "demo", null, null);

        when(tokenService.parseAndValidate("token")).thenReturn(claims);
        when(demoKeyRepository.findByKeyIdAndOrg("key-1", "acme"))
                .thenReturn(Optional.of(demoKey));

        assertThatThrownBy(() -> service.redeem("token", "127.0.0.1", "agent"))
                .isInstanceOf(DemoAccessException.class)
                .hasMessage("Demo key has been revoked");
    }

    @Test
    void redeemRejectsExpiredKey() {
        DemoKey demoKey = new DemoKey();
        demoKey.setKeyId("key-1");
        demoKey.setOrg("acme");
        demoKey.setExpiresAt(OffsetDateTime.now().minusDays(1));
        DemoClaims claims = new DemoClaims("acme", "key-1", "demo", null, null);

        when(tokenService.parseAndValidate("token")).thenReturn(claims);
        when(demoKeyRepository.findByKeyIdAndOrg("key-1", "acme"))
                .thenReturn(Optional.of(demoKey));

        assertThatThrownBy(() -> service.redeem("token", "127.0.0.1", "agent"))
                .isInstanceOf(DemoAccessException.class)
                .hasMessage("Demo key has expired");
    }

    @Test
    void redeemRejectsActivationLimit() {
        DemoKey demoKey = new DemoKey();
        demoKey.setKeyId("key-1");
        demoKey.setOrg("acme");
        demoKey.setActivations(3);
        demoKey.setMaxActivations(3);
        DemoClaims claims = new DemoClaims("acme", "key-1", "demo", null, null);

        when(tokenService.parseAndValidate("token")).thenReturn(claims);
        when(demoKeyRepository.findByKeyIdAndOrg("key-1", "acme"))
                .thenReturn(Optional.of(demoKey));

        assertThatThrownBy(() -> service.redeem("token", "127.0.0.1", "agent"))
                .isInstanceOf(DemoAccessException.class)
                .hasMessage("Demo key activation limit reached");
    }

    @Test
    void redeemCreatesKeyAndUserOnFirstUse() {
        DemoClaims claims = new DemoClaims("Acme Co", "key-1", "demo", null, null);
        when(tokenService.parseAndValidate("token")).thenReturn(claims);

        when(demoKeyRepository.findByKeyIdAndOrg("key-1", "Acme Co"))
                .thenReturn(Optional.empty());

        // save is called twice (once in newDemoKey, once in redeem) — return the passed entity both times
        when(demoKeyRepository.save(any(DemoKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        stubRoles();

        when(passwordEncoder.encode(any(String.class))).thenReturn("encoded");

        when(userAccountRepository.findByDemoOrg("Acme Co"))
                .thenReturn(Optional.empty());

        when(userAccountRepository.save(any(UserAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(authService.issueTokensForUser(any(UserAccount.class)))
                .thenReturn(new AuthResult(null, null));

        AuthResult result = service.redeem("token", "127.0.0.1", "agent");

        assertThat(result).isNotNull();

        // DemoKey is saved twice; assert on the second (post-redeem) save
        ArgumentCaptor<DemoKey> keyCaptor = ArgumentCaptor.forClass(DemoKey.class);
        verify(demoKeyRepository, times(2)).save(keyCaptor.capture());

        DemoKey savedKey = keyCaptor.getAllValues().get(1);
        assertThat(savedKey.getFirstUsedAt()).isNotNull();
        assertThat(savedKey.getExpiresAt()).isNotNull();
        assertThat(savedKey.getActivations()).isEqualTo(1);
        assertThat(savedKey.getLastUsedAt()).isNotNull();

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("acme-co@demo.pv");

        ArgumentCaptor<DemoRedemption> redemptionCaptor = ArgumentCaptor.forClass(DemoRedemption.class);
        verify(redemptionRepository).save(redemptionCaptor.capture());

        DemoRedemption redemption = redemptionCaptor.getValue();
        assertThat(redemption.getKeyId()).isEqualTo("key-1");
        assertThat(redemption.getOrg()).isEqualTo("Acme Co");
        assertThat(redemption.getIp()).isEqualTo("127.0.0.1");
        assertThat(redemption.getUserAgent()).isEqualTo("agent");
    }

    @Test
    void redeemCreatesDemoEmailFallbackWhenOrgIsBlank() {
        DemoClaims claims = new DemoClaims("!!!", "key-2", "demo", null, null);
        when(tokenService.parseAndValidate("token")).thenReturn(claims);
        when(demoKeyRepository.findByKeyIdAndOrg("key-2", "!!!"))
                .thenReturn(Optional.empty());
        when(demoKeyRepository.save(any(DemoKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        stubRoles();
        when(passwordEncoder.encode(any(String.class))).thenReturn("encoded");
        when(userAccountRepository.findByDemoOrg("!!!"))
                .thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(authService.issueTokensForUser(any(UserAccount.class)))
                .thenReturn(new AuthResult(null, null));

        service.redeem("token", "127.0.0.1", "agent");

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("demo@demo.pv");
    }

    @Test
    void redeemTruncatesLongOrgSlugInDemoEmail() {
        String org = "This Org Name Is So Extremely Long It Should Be Trimmed";
        DemoClaims claims = new DemoClaims(org, "key-3", "demo", null, null);
        when(tokenService.parseAndValidate("token")).thenReturn(claims);
        when(demoKeyRepository.findByKeyIdAndOrg("key-3", org))
                .thenReturn(Optional.empty());
        when(demoKeyRepository.save(any(DemoKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        stubRoles();
        when(passwordEncoder.encode(any(String.class))).thenReturn("encoded");
        when(userAccountRepository.findByDemoOrg(org))
                .thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(authService.issueTokensForUser(any(UserAccount.class)))
                .thenReturn(new AuthResult(null, null));

        service.redeem("token", "127.0.0.1", "agent");

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail())
                .isEqualTo("this-org-name-is-so-extremely-lo@demo.pv");
    }

    @Test
    void redeemUsesExistingKeyWhenSaveConflicts() {
        DemoClaims claims = new DemoClaims("acme", "key-4", "demo", Instant.now(), Instant.now().plusSeconds(3600));
        DemoKey existingKey = new DemoKey();
        existingKey.setKeyId("key-4");
        existingKey.setOrg("acme");
        existingKey.setExpiresAt(OffsetDateTime.now().plusDays(1));
        existingKey.setFirstUsedAt(OffsetDateTime.now().minusDays(1));
        existingKey.setActivations(1);
        existingKey.setMaxActivations(3);

        when(tokenService.parseAndValidate("token")).thenReturn(claims);
        when(demoKeyRepository.findByKeyIdAndOrg("key-4", "acme"))
                .thenReturn(Optional.empty(), Optional.of(existingKey));
        when(demoKeyRepository.save(any(DemoKey.class)))
                .thenThrow(new DataIntegrityViolationException("conflict"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount user = new UserAccount();
        user.setDemoOrg("acme");
        when(userAccountRepository.findByDemoOrg("acme"))
                .thenReturn(Optional.of(user));
        when(authService.issueTokensForUser(user))
                .thenReturn(new AuthResult(null, null));

        service.redeem("token", "127.0.0.1", "agent");

        assertThat(existingKey.getActivations()).isEqualTo(2);
    }

    @Test
    void findTokenDetailsReturnsRepositoryValue() {
        DemoKey demoKey = new DemoKey();
        demoKey.setKeyId("org-1");
        when(demoKeyRepository.findByKeyId("org-1"))
                .thenReturn(Optional.of(demoKey));

        Optional<DemoKey> result = service.findTokenDetails("org-1");

        assertThat(result).contains(demoKey);
    }

    private void stubRoles() {
        Role userRole = new Role();
        userRole.setName(RoleName.ROLE_USER);
        Role demoRole = new Role();
        demoRole.setName(RoleName.ROLE_DEMO);
        when(roleRepository.findByName(RoleName.ROLE_USER))
                .thenReturn(Optional.of(userRole));
        when(roleRepository.findByName(RoleName.ROLE_DEMO))
                .thenReturn(Optional.of(demoRole));
    }
}
