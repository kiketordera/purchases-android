# The Mate Club maintenance source

Based on RevenueCat Android SDK 10.28.1, upstream commit
`618bdeb1bfc99ac04f849604742de6ae1285d728`. Upstream history, license,
workflow configuration and the default automatic-sync behavior are retained.

The suite uses `DangerousSettings(autoSyncPurchases = false)` and disables
automatic in-app messages. In this mode, unsolicited Play updates do not post
receipts. An explicit checkout captures its owner, restore policy, initiating
callback and start time. Play results require matching account-hash and time
evidence, and completion stays attached to that captured operation even if the
product order or currently selected identity changes. Obsolete completions cannot
consume a newer callback. Explicit ordinary restore and sync remain available.

The additive experimental `recoverPendingPurchase` API supports a deliberate
same-owner recovery after process death. It queries Play itself, accepts a unique
purchased transaction matching the named SDK owner, SKU and original time, and
returns that transaction only after RevenueCat acknowledges its receipt.
Empty, pending, foreign, historical, future or ambiguous results return an error.
One-shot query and receipt callbacks prevent duplicate or obsolete provider posts.
This does not establish historical anonymous ownership or recover finished
consumables absent from Play's query.

Application admission fences, encrypted durable purchase intents, explicit restore
confirmation and uncertainty handling are still required. Disabling automatic
sync does not replace those application responsibilities.

The final focused verification ran 146 actual SDK tests with zero failures or skips:
15 manual purchase-ownership cases, 18 recovery cases and 113 unchanged common
SDK controls. The tests use the actual orchestrator/receipt boundary with mocked
Billing/RevenueCat transports; they do not claim a real paid Play transaction or
production RevenueCat receipt acceptance. Detekt passed without baseline growth.
API generation records one additive default-flavor method; the alternate API is
checked separately because upstream derives its output filename from task names.

Applications build this exact source commit into an isolated local Maven
repository as `com.revenuecat.purchases:purchases:10.28.1-mateclub.1`.
No SDK artifact is published to an external Maven repository by the suite helper.
Native app builds and store purchase acceptance remain separate release gates.
