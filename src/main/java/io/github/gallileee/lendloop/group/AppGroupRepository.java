package io.github.gallileee.lendloop.group;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppGroupRepository extends JpaRepository<AppGroup, Long> {

	Optional<AppGroup> findByInviteToken(String inviteToken);
}
