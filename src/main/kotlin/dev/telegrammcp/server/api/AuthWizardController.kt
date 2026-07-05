package dev.telegrammcp.server.api

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.telegrammcp.server.auth.AuthState
import dev.telegrammcp.server.auth.AuthWizardProperties
import dev.telegrammcp.server.auth.TelegramAuthStateHolder
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Loopback-only browser UI used by the explicit `telegram-mcp auth` command. */
@RestController
@Profile("auth-wizard")
class AuthWizardController(
    private val properties: AuthWizardProperties,
    private val stateHolder: TelegramAuthStateHolder,
) {

    @GetMapping("/setup", produces = [MediaType.TEXT_HTML_VALUE])
    fun setup(@RequestParam nonce: String): ResponseEntity<String> {
        validateNonce(nonce)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
                    "base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
            )
            .body(page(properties.nonce, properties.accountLabel, properties.method))
    }

    @GetMapping("/setup/qr.svg", produces = ["image/svg+xml"])
    fun qr(@RequestParam nonce: String): ResponseEntity<String> {
        validateNonce(nonce)
        val link = (stateHolder.getState() as? AuthState.WaitingQr)?.qrLink
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(renderQrSvg(link))
    }

    private fun validateNonce(supplied: String) {
        val valid = properties.enabled &&
            properties.nonce.isNotBlank() &&
            MessageDigest.isEqual(
                supplied.toByteArray(StandardCharsets.UTF_8),
                properties.nonce.toByteArray(StandardCharsets.UTF_8),
            )
        if (!valid) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid authentication wizard nonce")
    }

    private fun renderQrSvg(value: String): String {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 33, 33)
        val scale = 8
        val size = matrix.width * scale
        return buildString {
            append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" role="img" aria-label="Telegram login QR code">""")
            append("""<rect width="100%" height="100%" fill="#fff"/>""")
            append("""<path fill="#111" d="""")
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) append("M${x * scale},${y * scale}h${scale}v${scale}h-${scale}z")
                }
            }
            append(""""/></svg>""")
        }
    }

    private fun page(nonce: String, account: String, method: String): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>Telegram MCP authentication</title>
          <style>
            :root{color-scheme:light dark;font:16px/1.45 system-ui,sans-serif}
            body{margin:0;background:#0f172a;color:#e2e8f0}
            main{max-width:520px;margin:7vh auto;padding:28px;background:#1e293b;border-radius:18px;box-shadow:0 20px 60px #0006}
            h1{margin-top:0} label{display:block;margin:14px 0 6px}
            input,button{box-sizing:border-box;width:100%;padding:11px;border-radius:9px;border:1px solid #64748b;font:inherit}
            button{margin-top:18px;background:#2aabee;color:white;border:0;font-weight:700;cursor:pointer}
            #status{margin-top:18px;padding:12px;background:#0f172a;border-radius:9px;white-space:pre-wrap}
            #qr{display:none;width:264px;height:264px;margin:18px auto;background:white;padding:8px;border-radius:12px}
            .secret{font-family:inherit}.muted{color:#94a3b8}.success{color:#86efac}.error{color:#fca5a5}
          </style>
        </head>
        <body><main>
          <h1>Connect Telegram</h1>
          <p class="muted">Account label: <strong>${html(account)}</strong>. This page is bound to loopback and expires when authentication completes.</p>
          <form id="credentials">
            <label for="apiId">Telegram API ID</label><input id="apiId" inputmode="numeric" required autocomplete="off">
            <label for="apiHash">Telegram API hash</label><input id="apiHash" class="secret" type="password" required autocomplete="off">
            <div id="phoneWrap">
              <label for="phone">Phone number</label><input id="phone" autocomplete="tel" placeholder="+15551234567">
            </div>
            <button type="submit">Start ${if (method == "phone") "phone" else "QR"} authentication</button>
          </form>
          <form id="challenge" hidden>
            <label id="challengeLabel" for="challengeInput">Code</label>
            <input id="challengeInput" class="secret" type="password" autocomplete="one-time-code">
            <button type="submit">Continue</button>
          </form>
          <img id="qr" alt="Telegram login QR code">
          <div id="status">Waiting for credentials.</div>
        </main>
        <script>
          const nonce = ${js(nonce)};
          const method = ${js(method)};
          const headers = {'Content-Type':'application/json','X-Auth-Wizard-Nonce':nonce};
          const statusBox = document.getElementById('status');
          const credentials = document.getElementById('credentials');
          const challenge = document.getElementById('challenge');
          const qr = document.getElementById('qr');
          if(method === 'qr') document.getElementById('phoneWrap').hidden = true;
          async function api(path, body) {
            const response = await fetch(path,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined,cache:'no-store'});
            const data = await response.json();
            if(!response.ok) throw new Error(data.errorMessage || ('HTTP '+response.status));
            return data;
          }
          credentials.addEventListener('submit', async event => {
            event.preventDefault();
            credentials.querySelector('button').disabled = true;
            try {
              const body={apiId:Number(apiId.value),apiHash:apiHash.value};
              if(method==='phone') body.phoneNumber=phone.value;
              await api('/auth/credentials',body);
              if(method==='qr') await api('/auth/request-qr',{});
              apiHash.value='';
              poll();
            } catch(error) { showError(error); credentials.querySelector('button').disabled=false; }
          });
          challenge.addEventListener('submit', async event => {
            event.preventDefault();
            const input=document.getElementById('challengeInput');
            try {
              const state=await api('/auth/state');
              const path=state.state==='waitingPassword'?'/auth/submit-password':'/auth/submit-code';
              const key=state.state==='waitingPassword'?'password':'code';
              await api(path,{[key]:input.value});
              input.value=''; challenge.hidden=true; poll();
            } catch(error) { showError(error); }
          });
          function showError(error){statusBox.className='error';statusBox.textContent=error.message;}
          async function poll(){
            try{
              const state=await api('/auth/state');
              statusBox.className=''; statusBox.textContent='Telegram state: '+state.state;
              qr.style.display='none'; challenge.hidden=true;
              if(state.state==='waitingQr'){
                qr.src='/setup/qr.svg?nonce='+encodeURIComponent(nonce)+'&t='+Date.now(); qr.style.display='block';
              } else if(state.state==='waitingCode'||state.state==='waitingPassword'){
                challenge.hidden=false;
                challengeLabel.textContent=state.state==='waitingPassword'?'Telegram 2FA password':'Telegram login code';
              } else if(state.state==='ready'){
                statusBox.className='success';statusBox.textContent='Connected. You can close this tab.';
                credentials.hidden=true;return;
              } else if(state.state==='error'){throw new Error(state.errorMessage||'Authentication failed');}
              setTimeout(poll,900);
            }catch(error){showError(error);}
          }
        </script></body></html>
    """.trimIndent()

    private fun html(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun js(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
