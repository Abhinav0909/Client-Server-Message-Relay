package com.cisco.webex.domain.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClientDirectoryTest {

    private ClientDirectory newDirectory() {
        return new ClientDirectory(new ClientDirectory.Settings(1000));
    }

    @Test
    void getOrCreateReturnsTheSameInstanceOnRepeatedCalls() {
        ClientDirectory directory = newDirectory();

        Client first = directory.getOrCreate("alice");
        Client second = directory.getOrCreate("alice");

        assertThat(first).isSameAs(second);
        assertThat(first.getClientId()).isEqualTo("alice");
    }

    @Test
    void getOrCreateForDifferentIdsReturnsDifferentInstances() {
        ClientDirectory directory = newDirectory();

        Client alice = directory.getOrCreate("alice");
        Client bob = directory.getOrCreate("bob");

        assertThat(alice).isNotSameAs(bob);
    }

    @Test
    void findReturnsEmptyForUnknownClient() {
        ClientDirectory directory = newDirectory();
        assertThat(directory.find("ghost")).isEmpty();
    }

    @Test
    void findReturnsTheRegisteredClient() {
        ClientDirectory directory = newDirectory();
        Client created = directory.getOrCreate("alice");

        Optional<Client> found = directory.find("alice");

        assertThat(found).contains(created);
    }

    @Test
    void existsReflectsWhetherGetOrCreateWasCalled() {
        ClientDirectory directory = newDirectory();
        assertThat(directory.exists("alice")).isFalse();

        directory.getOrCreate("alice");

        assertThat(directory.exists("alice")).isTrue();
    }
}
