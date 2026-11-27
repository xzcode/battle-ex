package com.battleex;

import com.battleex.platform.AccountStore;
import com.battleex.platform.MongoAccounts;
import com.battleex.platform.Platform;
import com.battleex.platform.ReactiveWork;
import com.battleex.competition.ResultAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {MongoAutoConfiguration.class, MongoReactiveAutoConfiguration.class})
@EnableScheduling
public class BattleApplication {
    public static void main(String[] args) { SpringApplication.run(BattleApplication.class, args); }

    @Bean(destroyMethod = "close")
    AccountStore accountStore(@Value("${battle.mongo-uri:}") String uri,
                              @Value("${battle.mongo-db:battle_ex}") String database) {
        return uri.isBlank() ? new AccountStore.Memory() : new MongoAccounts(uri, database);
    }

    @Bean Platform platform(AccountStore accounts,ReactiveWork work,ObjectMapper mapper,
            @Value("${battle.competition.result-issuer:}") String issuer,
            @Value("${battle.competition.ranked-games:}") String games) {
        return new Platform(accounts,work,new ResultAuthority(issuer,games,mapper));
    }
}
