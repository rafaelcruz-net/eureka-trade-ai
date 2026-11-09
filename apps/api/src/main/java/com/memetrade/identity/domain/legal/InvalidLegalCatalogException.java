package com.memetrade.identity.domain.legal;

/**
 * The legal catalog cannot be used (BR1.1 to BR1.4): the message names the version, the file and the problem, never
 * the text itself. The infrastructure turns it into the start-up failure of the api (NFR7.1).
 */
public class InvalidLegalCatalogException extends IllegalStateException {

    public InvalidLegalCatalogException(String problem) {
        super(problem);
    }
}
