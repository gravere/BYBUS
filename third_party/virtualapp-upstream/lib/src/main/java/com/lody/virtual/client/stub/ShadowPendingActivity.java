package com.lody.virtual.client.stub;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.delegate.AppInstrumentation;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.compat.BundleCompat;
import com.lody.virtual.helper.compat.IntentCompat;
import com.lody.virtual.helper.compat.LimbusActivityCompat;
import com.lody.virtual.helper.compat.LimbusBillingUiPolicy;
import com.lody.virtual.helper.compat.LimbusBillingDiagnostics;
import com.lody.virtual.helper.compat.PendingUiState;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.remote.ClientConfig;
import com.lody.virtual.remote.IntentSenderData;
import com.lody.virtual.remote.IntentSenderExtData;
import com.lody.virtual.server.am.VActivityManagerService;

import java.lang.reflect.Method;

/**
 * 承接系统 PendingIntent，并将其中的目标与 Activity 结果桥接回虚拟应用。
 *
 * <p>Google 登录使用本 Activity 在容器引擎进程中接收系统回调，再启动虚拟 GMS 的
 * 身份选择页面，同时保留原始游戏 Activity 的结果回传关系。</p>
 *
 * @author Lody
 */

public class ShadowPendingActivity extends Activity {

    private static final int GOOGLE_IDENTITY_UI_REQUEST = PendingUiState.GOOGLE_IDENTITY;
    private static final int BILLING_UI_REQUEST = PendingUiState.BILLING;
    private static final String BILLING_IN_FLIGHT = "bybus.billing_ui_in_flight";
    private static final String PENDING_UI_REQUEST = "bybus.pending_ui_request";
    private final PendingUiState pendingUi = new PendingUiState();

    /**
     * 初始化 PendingIntent 桥接页面，并将目标请求分发到对应的虚拟 Activity。
     *
     * @param savedInstanceState Android 保存的 Activity 状态，可为空
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 该宿主页不经过虚拟应用 Instrumentation。必须在 super.onCreate() 前安装
        // ColorOS 兜底，避免生命周期返回后生成 PhoneWindow 导航栏时解引用空服务。
        AppInstrumentation.installOplusActivityManagerFallbackIfNeeded(this);
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingUi.restore(savedInstanceState.getInt(PENDING_UI_REQUEST,
                    savedInstanceState.getBoolean(BILLING_IN_FLIGHT, false) ? BILLING_UI_REQUEST : 0));
        }
        if (pendingUi.waiting()) {
            // Android retains the child/result relationship across recreation. Do not open a
            // second checkout or let this translucent bridge finish before the child returns.
            return;
        }
        Intent originalIntent = getIntent();
        Intent intent = new Intent(originalIntent);
        intent.setExtrasClassLoader(IntentSenderExtData.class.getClassLoader());
        Intent finalIntent = ComponentUtils.getIntentForIntentSender(intent);
        int userId = ComponentUtils.getUserIdForIntentSender(intent);
        IntentSenderExtData ext = intent.getParcelableExtra("_VA_|_ext_");
        IBinder senderToken = BundleCompat.getBinder(intent, "_VA_|_sender_");
        String bridgeKey = intent.getStringExtra("_VA_|_sender_key_");
        if (finalIntent == null || userId == -1) {
            Log.w("LimbusVA", "ShadowPendingActivity missing target onCreate/relaunch intent="
                    + originalIntent + " copiedIntent=" + intent);
            finish();
            return;
        }
        if (ext == null) {
            IntentSenderData bridgeData = senderToken == null
                    ? VActivityManager.get().getIntentSenderByBridgeKey(bridgeKey)
                    : VActivityManager.get().getIntentSender(senderToken);
            if (bridgeData != null && bridgeData.token != null) {
                ext = bridgeData.toExtData(bridgeData.token);
                senderToken = bridgeData.token;
                Log.i("LimbusVA", "Recovered IntentSender result bridge key=" + bridgeKey
                        + " sender=" + senderToken
                        + " resultTo=" + ext.resultTo
                        + " requestCode=" + ext.requestCode);
            }
        }
        ComponentUtils.clearVAData(intent);
        if ("android.nfc.action.NDEF_DISCOVERED".equals(intent.getAction())
                || "android.nfc.action.TAG_DISCOVERED".equals(intent.getAction())
                | "android.nfc.action.TECH_DISCOVERED".equals(intent.getAction())) {
            if (intent.getData() != null) {
                finalIntent.setDataAndType(intent.getData(), intent.getType());
            }
            if (intent.getCategories() != null) {
                for (String g : intent.getCategories()) {
                    finalIntent.addCategory(g);
                }
            }
            if (intent.getAction() != null) {
                finalIntent.setAction(intent.getAction());
            }
        }
        if (intent.getExtras() != null) {
            try {
                finalIntent.putExtras(intent.getExtras());
            } catch (Throwable e) {
                //unknown
            }
        }
        if (isGoogleIdentityUi(finalIntent)) {
            Log.i("LimbusVA", "Keep Google Identity UI inside the virtual GMS process ext="
                    + ext + " sender=" + (ext == null ? null : ext.sender));
        }
        if (ext != null && ext.sender != null) {
            IntentSenderData data = VActivityManager.get().getIntentSender(ext.sender);
            if (data == null) {
                Log.w("LimbusVA", "Missing IntentSenderData for sender=" + ext.sender
                        + " action=" + finalIntent.getAction());
                finish();
                return;
            }
            Intent fillIn = ext.fillIn;
            if (fillIn != null) {
                finalIntent.fillIn(fillIn, data.flags);
            }
            int flagsMask = ext.flagsMask;
            int flagsValues = ext.flagsValues;
            flagsMask &= ~IntentCompat.IMMUTABLE_FLAGS;
            flagsValues &= flagsMask;
            finalIntent.setFlags((finalIntent.getFlags() & ~flagsMask) | flagsValues);
            restoreLimbusGoogleOAuthClient(finalIntent);
            if (isGoogleIdentityUi(finalIntent)) {
                Log.i("LimbusVA", "Dispatch filled Google Identity UI through VAMS ext=" + ext
                        + " sender=" + ext.sender + " resultTo=" + ext.resultTo);
            }
            ActivityInfo info = VirtualCore.get().resolveActivityInfo(finalIntent, data.userId);
            if (info != null && LimbusBillingUiPolicy.isCheckoutTarget(data.creator, info.packageName, info.name)) {
                String sourcePackage = ext.resultTo == null ? null
                        : VActivityManager.get().getPackageForToken(ext.resultTo);
                if (LimbusBillingUiPolicy.canRelayResult(sourcePackage, ext.resultTo != null, ext.requestCode)) {
                    pendingUi.begin(BILLING_UI_REQUEST);
                    if (startVirtualPendingUiForResult(finalIntent, info, data.userId, ext, BILLING_UI_REQUEST)) {
                        LimbusBillingDiagnostics.record("event=billing_ui_bridge_started");
                        return;
                    }
                    pendingUi.finish(BILLING_UI_REQUEST);
                    LimbusBillingDiagnostics.record("event=billing_ui_bridge_start_failed");
                    // A real launch failure is an error, not a successful or fabricated purchase.
                    setResult(RESULT_CANCELED, new Intent().putExtra("RESPONSE_CODE", 6));
                    finish();
                    return;
                }
                LimbusBillingDiagnostics.record("event=billing_ui_bridge_source_unavailable");
            }
            if (isGoogleIdentityUi(finalIntent)) {
                pendingUi.begin(GOOGLE_IDENTITY_UI_REQUEST);
                if (startVirtualPendingUiForResult(finalIntent, info, data.userId, ext, GOOGLE_IDENTITY_UI_REQUEST)) {
                    return;
                }
                pendingUi.finish(GOOGLE_IDENTITY_UI_REQUEST);
            }
            int res = VActivityManager.get().startActivity(finalIntent, info, ext.resultTo, ext.options, ext.resultWho, ext.requestCode, data.userId);
            if (res != 0 && ext.resultTo != null && ext.requestCode > 0) {
                VActivityManager.get().sendCancelActivityResult(ext.resultTo, ext.resultWho, ext.requestCode);
            }
        } else {
            restoreLimbusGoogleOAuthClient(finalIntent);
            VActivityManager.get().startActivity(finalIntent, userId);
        }
        finish();
    }

    private void restoreLimbusGoogleOAuthClient(Intent intent) {
        if (LimbusActivityCompat.restoreGoogleOAuthClientPackage(
                intent,
                VirtualCore.get().getHostPkg(),
                "com.ProjectMoon.LimbusCompany")) {
            Log.i("LimbusVA", "Restored Limbus OAuth client package for Google Identity UI action="
                    + intent.getAction());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(PENDING_UI_REQUEST, pendingUi.savedRequest());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (!pendingUi.finish(requestCode)) return;
        if (requestCode == BILLING_UI_REQUEST) {
            LimbusBillingDiagnostics.record("event=billing_ui_bridge_result resultCode=" + resultCode
                    + " dataPresent=" + (data != null ? 1 : 0));
            // Preserve the complete real cancellation/error/purchase result without inspecting it.
            setResult(resultCode, data);
            finish();
            return;
        }
        if (requestCode == GOOGLE_IDENTITY_UI_REQUEST) {
            Log.i("LimbusVA", "Relay Google Identity UI result resultCode=" + resultCode
                    + " dataPresent=" + (data != null));
            setResult(resultCode, data);
            finish();
        }
    }

    private boolean isGoogleIdentityUi(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        if (!"com.google.android.gms.auth.api.credentials.GOOGLE_SIGN_IN".equals(action)
                && !"com.google.android.gms.auth.api.credentials.ASSISTED_SIGNIN".equals(action)) {
            return false;
        }
        return intent.getComponent() == null
                || "com.google.android.gms".equals(intent.getComponent().getPackageName());
    }

    private boolean startVirtualPendingUiForResult(Intent intent, ActivityInfo info, int userId,
                                                   IntentSenderExtData ext, int requestCode) {
        if (info == null) {
            Log.w("LimbusVA", "Unable to resolve virtual pending UI: " + intent);
            return false;
        }
        try {
            ClientConfig clientConfig = VActivityManagerService.get().initProcess(
                    info.packageName,
                    ComponentUtils.getProcessName(info),
                    userId,
                    VActivityManager.PROCESS_TYPE_ACTIVITY);
            if (clientConfig == null) {
                Log.w("LimbusVA", "Unable to init virtual pending UI process: "
                        + info.packageName + "/" + info.name);
                return false;
            }
            IBinder token = mirror.android.app.Activity.mToken.get(this);
            if (requestCode == BILLING_UI_REQUEST) {
                intent = new Intent(intent);
                intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            Intent targetIntent = VActivityManagerService.get().getStartStubActivityIntentInner(
                    intent,
                    false,
                    clientConfig.vpid,
                    userId,
                    token,
                    info);
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
            Log.i("LimbusVA", "Launch virtual pending UI for result target="
                    + info.packageName + "/" + info.name
                    + " vpid=" + clientConfig.vpid
                    + " token=" + token
                    + " extResultTo=" + (ext == null ? null : ext.resultTo)
                    + " extRequestCode=" + (ext == null ? 0 : ext.requestCode)
                    + " stub=" + targetIntent);
            if (ext != null && ext.options != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                startActivityForResult(targetIntent, requestCode, ext.options);
            } else {
                startActivityForResult(targetIntent, requestCode);
            }
            return true;
        } catch (Throwable error) {
            Log.e("LimbusVA", "Unable to launch virtual pending UI for result", error);
            return false;
        }
    }

    private boolean startSystemGoogleIdentityUi(Intent intent, IntentSenderExtData ext) {
        try {
            intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Class<?> activityTaskManager = Class.forName("android.app.ActivityTaskManager");
            Method getService = activityTaskManager.getDeclaredMethod("getService");
            getService.setAccessible(true);
            Object service = getService.invoke(null);
            Object appThread = mirror.android.app.ActivityThread.getApplicationThread.call(
                    VirtualCore.mainThread());
            for (Method method : service.getClass().getMethods()) {
                if (!"startActivity".equals(method.getName())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                Object[] args = new Object[types.length];
                int stringIndex = 0;
                int intIndex = 0;
                boolean supported = true;
                for (int i = 0; i < types.length; i++) {
                    Class<?> type = types[i];
                    if (IInterface.class.isAssignableFrom(type)) {
                        args[i] = appThread;
                    } else if (type == String.class) {
                        if (stringIndex == 0) {
                            args[i] = VirtualCore.get().getHostPkg();
                        } else if (stringIndex == 2) {
                            args[i] = intent.resolveTypeIfNeeded(getContentResolver());
                        } else if (stringIndex == 3) {
                            args[i] = ext.resultWho;
                        }
                        stringIndex++;
                    } else if (type == Intent.class) {
                        args[i] = intent;
                    } else if (type == IBinder.class) {
                        args[i] = ext.resultTo;
                    } else if (type == int.class || type == Integer.class) {
                        args[i] = intIndex++ == 0 ? ext.requestCode : 0;
                    } else if (type == Bundle.class) {
                        args[i] = ext.options;
                    } else if (type.isPrimitive()) {
                        supported = false;
                        break;
                    }
                }
                if (!supported) {
                    continue;
                }
                Object result = method.invoke(service, args);
                Log.i("LimbusVA", "Launched Google Identity UI in system GMS result=" + result
                        + " resultTo=" + ext.resultTo + " requestCode=" + ext.requestCode);
                return true;
            }
        } catch (Throwable error) {
            Log.e("LimbusVA", "Unable to launch Google Identity UI in system GMS", error);
        }
        return false;
    }
}
