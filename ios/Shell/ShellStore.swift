/*
 * Uygulama içi satın alma (StoreKit 2). Yalnızca sayfa istediğinde çalışır: sayfa
 * webkit.messageHandlers.shell.postMessage({op:'iapProducts'|'iapBuy'|'iapRestore', ...}) göndermedikçe
 * StoreKit'e hiç dokunulmaz. Sonuçlar sayfaya window.__billing.products / purchased / owned ile döner
 * (Android'deki AndroidTTS.billing* köprüsüyle aynı sözleşme).
 * Telif Hakkı (c) 2026 Egemen Çalıkoğlu. Tüm hakları saklıdır.
 */
import Foundation
import StoreKit
import WebKit

@MainActor
final class ShellStore {
    private weak var web: WKWebView?
    private var updatesTask: Task<Void, Never>?
    private var sentInitialOwned = false
    /// Bu oturumda sayfaya verilmiş işlemler (aynı satın alma iki kez verilmesin)
    private var delivered = Set<UInt64>()

    init(web: WKWebView) {
        self.web = web
    }

    func handle(_ op: String, _ body: [String: Any]) {
        startUpdates()
        switch op {
        case "iapProducts":
            let ids = (body["ids"] as? [Any] ?? []).compactMap { $0 as? String }
            Task { await self.products(ids) }
        case "iapBuy":
            guard let id = body["id"] as? String, !id.isEmpty else { return }
            Task { await self.buy(id) }
        case "iapRestore":
            Task { await self.restore() }
        default:
            break
        }
    }

    // MARK: - İşlemler

    private func products(_ ids: [String]) async {
        var list: [[String: Any]] = []
        if !ids.isEmpty, let found = try? await Product.products(for: ids) {
            for p in found {
                let micros = Int(truncating: (p.price * 1_000_000) as NSDecimalNumber)
                list.append([
                    "id": p.id,
                    "title": p.displayName,
                    "price": p.displayPrice,
                    "micros": micros,
                    "currency": p.priceFormatStyle.currencyCode,
                ])
            }
        }
        _ = await js("products", [list])
        if !sentInitialOwned {
            sentInitialOwned = true
            await deliverUnfinished()
            await postOwned()
        }
    }

    private func buy(_ id: String) async {
        do {
            guard let product = try await Product.products(for: [id]).first else {
                _ = await js("purchased", [id, false, "error:notfound"])
                return
            }
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                if case .verified(let transaction) = verification {
                    await deliver(transaction, info: "")
                } else {
                    _ = await js("purchased", [id, false, "error:unverified"])
                }
            case .userCancelled:
                _ = await js("purchased", [id, false, "cancel"])
            case .pending:
                _ = await js("purchased", [id, false, "pending"])
            @unknown default:
                _ = await js("purchased", [id, false, "error"])
            }
        } catch {
            _ = await js("purchased", [id, false, "error"])
        }
    }

    private func restore() async {
        // AppStore.sync() gerekirse Apple hesabı girişi ister; yalnızca kullanıcı "geri yükle" dediğinde çağrılır.
        try? await AppStore.sync()
        await deliverUnfinished()
        await postOwned()
    }

    // MARK: - Yardımcılar

    /// Daha sonra tamamlanan (ör. ebeveyn onayı, "pending") ya da uygulama kapanırken yarım kalan satın almalar.
    private func startUpdates() {
        guard updatesTask == nil else { return }
        updatesTask = Task { [weak self] in
            for await result in Transaction.updates {
                guard case .verified(let transaction) = result else { continue }
                guard let self = self else { return }
                await self.deliver(transaction, info: "")
            }
        }
    }

    /// Bitirilmemiş işlemler (ör. sayfa hazır değilken gelen jeton satın alması) yeniden verilir.
    private func deliverUnfinished() async {
        for await result in Transaction.unfinished {
            guard case .verified(let transaction) = result else { continue }
            await deliver(transaction, info: "restore")
        }
    }

    private func postOwned() async {
        var owned: [String] = []
        for await result in Transaction.currentEntitlements {
            guard case .verified(let t) = result else { continue }
            if t.productType == .nonConsumable && t.revocationDate == nil && !owned.contains(t.productID) {
                owned.append(t.productID)
            }
        }
        _ = await js("owned", [owned])
    }

    /// Satın alma sayfaya bildirilir, sonra işlem bitirilir. Sayfa henüz hazır değilse (window.__billing yok)
    /// işlem bitirilmez: StoreKit onu daha sonra (sonraki açılışta ya da geri yüklemede) yeniden verir.
    private func deliver(_ transaction: Transaction, info: String) async {
        if transaction.revocationDate != nil {
            await transaction.finish()
            return
        }
        // Önce işaretlenir: aynı işlem iki yoldan (updates / unfinished) aynı anda gelirse yalnızca biri verir.
        guard !delivered.contains(transaction.id) else { return }
        delivered.insert(transaction.id)
        let ok = await js("purchased", [transaction.productID, true, info])
        guard ok else {
            delivered.remove(transaction.id)
            return
        }
        await transaction.finish()
    }

    /// window.__billing.<name>(args...) çağrılır. Sayfada __billing tanımlıysa true döner.
    @discardableResult
    private func js(_ name: String, _ args: [Any]) async -> Bool {
        guard let web = web,
              let data = try? JSONSerialization.data(withJSONObject: args),
              let list = String(data: data, encoding: .utf8) else { return false }
        let script = """
        (function () {
          var b = window.__billing;
          if (!b || typeof b.\(name) !== 'function') return false;
          try { b.\(name).apply(b, \(list)); } catch (e) {}
          return true;
        })()
        """
        return await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            web.evaluateJavaScript(script) { result, _ in
                cont.resume(returning: (result as? Bool) == true)
            }
        }
    }
}
