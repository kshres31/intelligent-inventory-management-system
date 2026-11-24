package com.kshres31.inventory.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads passwords without echo from an interactive terminal, or one line per prompt from
 * standard input when piped (for scripted setup). Passwords never come from arguments or
 * environment variables, which leak through shell history and process listings.
 */
public final class ConsolePasswordPrompt implements AuthCommand.PasswordPrompt {
    private BufferedReader stdin;

    @Override
    public char[] read(String message) {
        var console = System.console();
        if (console != null) {
            char[] password = console.readPassword("%s", message);
            if (password == null) {
                throw new IllegalArgumentException("No password entered");
            }
            return password;
        }
        try {
            if (stdin == null) {
                stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            }
            String line = stdin.readLine();
            if (line == null) {
                throw new IllegalArgumentException("No password provided on standard input");
            }
            return line.toCharArray();
        } catch (IOException error) {
            throw new UncheckedIOException("Could not read password", error);
        }
    }
}
