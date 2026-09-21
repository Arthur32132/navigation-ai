package dev.navigationai.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface BotService {
    BotHandle create(BotCreateOptions options);
    Optional<BotHandle> find(UUID id);
    Optional<BotHandle> find(String name);
    Collection<BotHandle> bots();
}
