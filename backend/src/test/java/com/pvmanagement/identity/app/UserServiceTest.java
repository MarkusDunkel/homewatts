package com.pvmanagement.identity.app;

import com.pvmanagement.identity.domain.Role;
import com.pvmanagement.identity.domain.RoleName;
import com.pvmanagement.identity.domain.UpdateProfileRequest;
import com.pvmanagement.identity.domain.UserAccount;
import com.pvmanagement.identity.infra.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void updateProfileReturnsUpdatedProfile() {
        var user = new UserAccount();
        user.setId(1L);
        user.setEmail("user@example.com");
        user.setDisplayName("Old Name");
        var role = new Role();
        role.setName(RoleName.ROLE_USER);
        user.getRoles().add(role);

        when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userAccountRepository.save(any(UserAccount.class))).thenReturn(user);

        var result = userService.updateProfile("user@example.com", new UpdateProfileRequest("New Name"));

        assertThat(result.displayName()).isEqualTo("New Name");
        assertThat(result.roles()).contains("ROLE_USER");
    }
}
