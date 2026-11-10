package com.memetrade.privacy.domain.request;

import com.memetrade.identity.api.Principal;
import java.util.Objects;

/** Who changed a request (BR4.6): the person, an administrator or the system (the timers job). */
public sealed interface Actor {

    /** {@code PERSON}, {@code ADMIN} or {@code SYSTEM}. */
    String kind();

    static Actor person(Principal principal) {
        return new Person(principal);
    }

    static Actor admin(Principal principal) {
        return new Admin(principal);
    }

    static Actor system() {
        return new SystemActor();
    }

    record Person(Principal principal) implements Actor {
        public Person {
            Objects.requireNonNull(principal, "principal");
        }

        @Override
        public String kind() {
            return "PERSON";
        }
    }

    record Admin(Principal principal) implements Actor {
        public Admin {
            Objects.requireNonNull(principal, "principal");
        }

        @Override
        public String kind() {
            return "ADMIN";
        }
    }

    record SystemActor() implements Actor {
        @Override
        public String kind() {
            return "SYSTEM";
        }
    }
}
