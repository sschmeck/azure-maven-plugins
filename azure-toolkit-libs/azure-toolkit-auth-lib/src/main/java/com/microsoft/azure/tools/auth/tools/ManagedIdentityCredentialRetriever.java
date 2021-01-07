/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azure.tools.auth.tools;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.CredentialUnavailableException;
import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.microsoft.azure.tools.auth.AuthHelper;
import com.microsoft.azure.tools.auth.exception.LoginFailureException;
import com.microsoft.azure.tools.auth.model.AuthMethod;
import com.microsoft.azure.tools.auth.model.AzureCredentialWrapper;

public class ManagedIdentityCredentialRetriever extends AbstractCredentialRetriever {
    public AzureCredentialWrapper retrieve() throws LoginFailureException {
        try {
            ManagedIdentityCredential managedIdentityCredential = new ManagedIdentityCredentialBuilder().build();
            // test the token
            managedIdentityCredential.getToken(new TokenRequestContext().addScopes("qux/.default")).block();
            return new AzureCredentialWrapper(AuthMethod.MANAGED_IDENTITY, managedIdentityCredential,
                    AuthHelper.parseAzureEnvironment(null));
        } catch (CredentialUnavailableException ex) {
            throw new LoginFailureException(ex.getMessage(), ex);
        }

    }
}