package com.pvmanagement.demoAccess.app;

import com.pvmanagement.demoAccess.domain.DemoAccessProperties;
import com.pvmanagement.demoAccess.domain.DemoKey;
import com.pvmanagement.demoAccess.domain.DemoRedemption;
import com.pvmanagement.demoAccess.domain.DemoAccessException;
import com.pvmanagement.demoAccess.domain.DemoClaims;
import com.pvmanagement.demoAccess.domain.DemoTokenService;
import com.pvmanagement.identity.domain.Role;
import com.pvmanagement.identity.domain.RoleName;
import com.pvmanagement.identity.domain.UserAccount;
import com.pvmanagement.demoAccess.infra.DemoKeyRepository;
import com.pvmanagement.demoAccess.infra.DemoRedemptionRepository;
import com.pvmanagement.identity.infra.RoleRepository;
import com.pvmanagement.identity.infra.UserAccountRepository;
import com.pvmanagement.auth.domain.AuthResult;
import com.pvmanagement.auth.app.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Profile("!collector")
public class DemoAccessService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoAccessService.class);

    private final DemoTokenService tokenService;
    private final DemoKeyRepository demoKeyRepository;
    private final DemoRedemptionRepository redemptionRepository;
    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final DemoAccessProperties properties;

    public DemoAccessService(DemoTokenService tokenService, DemoKeyRepository demoKeyRepository, DemoRedemptionRepository redemptionRepository, UserAccountRepository userAccountRepository, RoleRepository roleRepository, PasswordEncoder passwordEncoder, AuthService authService, DemoAccessProperties properties) {
        this.tokenService = tokenService;
        this.demoKeyRepository = demoKeyRepository;
        this.redemptionRepository = redemptionRepository;
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.properties = properties;
    }

    @Transactional
    public AuthResult redeem(String token, String ip, String userAgent) {
        DemoClaims claims = tokenService.parseAndValidate(token);
        if (!"demo".equals(claims.scope())) {
            throw new DemoAccessException("Demo token has invalid scope");
        }

        DemoKey demoKey = demoKeyRepository.findByKeyIdAndOrg(claims.keyId(),
                                                              claims.org())
                                           .orElseGet(() -> newDemoKey(claims));

        if (demoKey.isRevoked()) {
            throw new DemoAccessException("Demo key has been revoked");
        }
        if (demoKey.getExpiresAt() != null && demoKey.getExpiresAt()
                                                     .isBefore(OffsetDateTime.now())) {
            throw new DemoAccessException("Demo key has expired");
        }
        if (demoKey.getActivations() >= demoKey.getMaxActivations()) {
            throw new DemoAccessException("Demo key activation limit reached");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (demoKey.getFirstUsedAt() == null) {
            demoKey.setFirstUsedAt(now);
            if (demoKey.getExpiresAt() == null) {
                demoKey.setExpiresAt(now.plusDays(properties.getKeyValidDays()));
            }
        }
        demoKey.setLastUsedAt(now);
        demoKey.setActivations(demoKey.getActivations() + 1);
        demoKeyRepository.save(demoKey);

        UserAccount user = findOrCreateDemoUser(claims.org(),
                                                demoKey);
        user.setDemoExpiresAt(demoKey.getExpiresAt());

        DemoRedemption redemption = new DemoRedemption();
        redemption.setKeyId(demoKey.getKeyId());
        redemption.setOrg(demoKey.getOrg());
        redemption.setIp(ip);
        redemption.setUserAgent(userAgent);
        redemptionRepository.save(redemption);

        LOGGER.info("Demo key {} redeemed for org {} from {}",
                    demoKey.getKeyId(),
                    demoKey.getOrg(),
                    ip);
        return authService.issueTokensForUser(user);
    }

    private DemoKey newDemoKey(DemoClaims claims) {
        DemoKey demoKey = new DemoKey();
        demoKey.setKeyId(claims.keyId());
        demoKey.setOrg(claims.org());
        demoKey.setMaxActivations(properties.getDefaultMaxActivations());
        if (claims.issuedAt() != null) {
            demoKey.setIssuedAt(OffsetDateTime.ofInstant(claims.issuedAt(),
                                                         ZoneOffset.UTC));
        }
        if (claims.expiresAt() != null) {
            demoKey.setExpiresAt(OffsetDateTime.ofInstant(claims.expiresAt(),
                                                          ZoneOffset.UTC));
        }
        try {
            return demoKeyRepository.save(demoKey);
        } catch (DataIntegrityViolationException ex) {
            LOGGER.debug("Demo key already exists for org {} and key {}",
                         claims.org(),
                         claims.keyId());
            return demoKeyRepository.findByKeyIdAndOrg(claims.keyId(),
                                                       claims.org())
                                    .orElseThrow(() -> ex);
        }
    }

    public Optional<DemoKey> findTokenDetails(String org) {
        return demoKeyRepository.findByKeyId(org);
    }

    private UserAccount findOrCreateDemoUser(String org, DemoKey demoKey) {
        return userAccountRepository.findByDemoOrg(org)
                                    .orElseGet(() -> createDemoUser(org,
                                                                    demoKey));
    }

    private UserAccount createDemoUser(String org, DemoKey demoKey) {
        UserAccount user = new UserAccount();
        user.setEmail(buildDemoEmail(org));
        user.setPassword(passwordEncoder.encode(UUID.randomUUID()
                                                    .toString()));
        user.setDisplayName(org);
        user.setDemoOrg(org);
        user.setDemoExpiresAt(demoKey.getExpiresAt());

        Role userRole = roleRepository.findByName(RoleName.ROLE_USER)
                                      .orElseThrow(() -> new IllegalStateException("ROLE_USER not seeded"));
        Role demoRole = roleRepository.findByName(RoleName.ROLE_DEMO)
                                      .orElseThrow(() -> new IllegalStateException("ROLE_DEMO not seeded"));

        user.getRoles()
            .addAll(Set.of(userRole,
                           demoRole));
        return userAccountRepository.save(user);
    }

    private String buildDemoEmail(String org) {
        String slug = org.toLowerCase(Locale.ROOT)
                         .replaceAll("[^a-z0-9]+",
                                     "-");
        slug = slug.replaceAll("^-+",
                               "")
                   .replaceAll("-+$",
                               "");
        if (slug.isBlank()) {
            slug = "demo";
        }
        if (slug.length() > 32) {
            slug = slug.substring(0,
                                  32);
        }
        return slug + "@demo.pv";
    }
}
