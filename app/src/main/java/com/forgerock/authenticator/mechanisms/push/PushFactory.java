/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */

package com.forgerock.authenticator.mechanisms.push;

import android.content.Context;
import android.widget.Toast;

import com.forgerock.authenticator.R;
import com.forgerock.authenticator.mechanisms.MechanismCreationException;
import com.forgerock.authenticator.mechanisms.base.Mechanism;
import com.forgerock.authenticator.mechanisms.base.MechanismFactory;
import com.forgerock.authenticator.mechanisms.base.UriParser;
import com.forgerock.authenticator.notifications.PushNotification;
import com.forgerock.authenticator.storage.IdentityModel;
import com.forgerock.authenticator.utils.MessageUtils;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;

import org.json.JSONException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import roboguice.RoboGuice;

/**
 * Responsible for generating instances of {@link Push}.
 *
 * Understands the concept of a version number associated with a Push mechanism
 * and will parse the URI according to this.
 */
public class PushFactory extends MechanismFactory {
    private PushAuthMapper mapper = new PushAuthMapper();
    private InstanceID instanceID;

    protected PushFactory(Context context, IdentityModel model, InstanceID instanceID) {
        super(context, model, new PushInfo());
        this.instanceID = instanceID;
    }

    @Override
    protected Mechanism.PartialMechanismBuilder createFromUriParameters(
            int version, String mechanismUID, Map<String, String> map) throws MechanismCreationException {
        if (version == 1) {
            String registrationEndpoint = map.get(PushAuthMapper.REG_ENDPOINT_KEY);
            String authenticationEndpoint = map.get(PushAuthMapper.AUTH_ENDPOINT_KEY);
            String base64Secret = map.get(PushAuthMapper.BASE_64_SHARED_SECRET_KEY);
            String base64Challenge = map.get(PushAuthMapper.BASE_64_CHALLENGE_KEY);
            String amlbCookie = map.get(PushAuthMapper.AM_LOAD_BALANCER_COOKIE_KEY);

            String messageId = get(map, PushAuthMapper.MESSAGE_ID_KEY, null);


            // TODO: AME-9928 check should be performed in on-resume as well.
            if (!checkPlayServices()) {
                throw new MechanismCreationException("Google play services not enabled");
            }
            String token;
            try {
                token = instanceID.getToken(getContext().getString(R.string.gcm_defaultSenderId),
                        GoogleCloudMessaging.INSTANCE_ID_SCOPE, null);
            } catch (IOException e) {
                throw new MechanismCreationException("Failed to retrieve GCM token.", e);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("deviceId", token);
            data.put("deviceType", "android");
            data.put("communicationType", "gcm");
            data.put("mechanismUid", mechanismUID);
            data.put("response", PushNotification.generateChallengeResponse(base64Secret, base64Challenge));

            MessageUtils messageUtils = RoboGuice.getInjector(getContext()).getInstance(MessageUtils.class);

            try {
                int returnCode = messageUtils.respond(registrationEndpoint, amlbCookie, base64Secret, messageId, data);
                if (returnCode != 200) {
                    throw new MechanismCreationException("Communication with server returned " +
                            returnCode + " code.");
                }
            } catch (IOException | JSONException e) {
                throw new MechanismCreationException("Failed to register with server.", e);
            }

            Push.PushBuilder pushBuilder = Push.builder().setAuthEndpoint(authenticationEndpoint).setBase64Secret(base64Secret);

            return pushBuilder;
        } else {
            throw new MechanismCreationException("Unknown version: " + version);
        }
    }

    @Override
    protected UriParser getParser() {
        return mapper;
    }

    @Override
    public Mechanism.PartialMechanismBuilder restoreFromParameters(int version, Map<String, String> map) throws MechanismCreationException {
        if (version == 1) {
            return Push.builder()
                    .setOptions(map);
        } else {
            throw new MechanismCreationException("Unknown version: " + version);
        }
    }

    // TODO: AME-9928 should seek to upgrade this functionality
    private boolean checkPlayServices() {
        Context context = getContext();
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        int resultCode = apiAvailability.isGooglePlayServicesAvailable(context);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (apiAvailability.isUserResolvableError(resultCode)) {
                apiAvailability.showErrorNotification(context, resultCode);
            } else {
                Toast.makeText(context.getApplicationContext(),
                        R.string.googleplay_load_error_message, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        return true;
    }
}
