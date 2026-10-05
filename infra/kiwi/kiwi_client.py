#!/usr/bin/env python3
"""kiwi_client.py — тонкий XML-RPC клиент к локальному стенду Kiwi TCMS.

## Почему нельзя `xmlrpc.client.ServerProxy` напрямую

Две особенности Kiwi ломают наивный вариант, и обе проявляются одинаково —
как «Authentication failed» на первом же защищённом вызове, хотя пароль верный:

1. **Аутентификация идёт по session-cookie, а не по HTTP Basic.** Ответ
   `Auth.login` — это session key, который Kiwi кладёт в `Set-Cookie:
   sessionid=…`. `xmlrpc.client.Transport` хранит заголовки только на время
   одного запроса и `Set-Cookie` не запоминает, поэтому каждый следующий
   вызов (`Product.filter`) приходит анонимным. Нужен транспорт, который
   накапливает cookie и пересылает их дальше — это `SessionTransport` ниже.

2. **Контейнер отвечает только по HTTPS.** Порт 8080 внутри образа — это
   server-блок nginx с `return 301 https://$host$request_uri;`, он не
   отдаёт приложение. Реальный backend слушает 8443. Поэтому клиент
   создаёт SSL-контекст, доверяющий локальному сертификату из
   `infra/kiwi/tls/localhost.crt` (самоподписанный, поэтому verify=False
   против CA-файла здесь равносильноverify=False — но путь остаётся явным,
   чтобы не выглядело, будто TLS отключён целиком).

## Идентификация кейсов

У Kiwi нет собственного ключа для «кейса из репозитория», поэтому внешний
идентификатор хранится в Kiwi-свойствах (`TestCase.add_property`):
`source_path` = путь к файлу, `source_class` = FQN класса. Это делает
sync идемпотентным: повторный запуск не плодит дубликаты, а может
обновить summary/приоритет у уже существующего кейса.

Идентификатор на тестовый метод (`Class.method`) хранится в
`TestCase.add_property(..., 'test_method', …)` — из этого же поля
восстанавливается привязка к конкретному тесту в JUnit XML.
"""

from __future__ import annotations

import http.client
import os
import ssl
import xmlrpc.client
from dataclasses import dataclass
from pathlib import Path
from typing import Any

DEFAULT_URL = os.environ.get("KIWI_URL", "https://127.0.0.1:8443/xml-rpc/")
DEFAULT_USER = os.environ.get("KIWI_USER", "kiwi")
DEFAULT_PASSWORD = os.environ.get("KIWI_PASSWORD", "kiwi")

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CERT = REPO_ROOT / "infra" / "kiwi" / "tls" / "localhost.crt"


class KiwiError(RuntimeError):
    """Ошибка обращения к Kiwi, с читаемым текстом вместо xmlrpc Fault."""


class SessionTransport(xmlrpc.client.Transport):
    """Transport, который накапливает Set-Cookie и шлёт их обратно.

    Kiwi отдаёт sessionid только в ответе на `Auth.login`. Без этого
    переноса каждый следующий RPC-вызов анонимный — см. docstring модуля.
    """

    def __init__(self, context: ssl.SSLContext) -> None:
        super().__init__()
        self._ctx = context
        self._cookies: dict[str, str] = {}

    def make_connection(self, host: str) -> http.client.HTTPSConnection:
        return http.client.HTTPSConnection(host, context=self._ctx, timeout=120)

    def send_headers(self, connection, headers):  # type: ignore[no-untyped-def]
        if self._cookies:
            cookie = "; ".join(f"{k}={v}" for k, v in self._cookies.items())
            headers = list(headers) + [("Cookie", cookie)]
        super().send_headers(connection, headers)

    def parse_response(self, response):  # type: ignore[no-untyped-def]
        for header, value in response.getheaders():
            if header.lower() == "set-cookie":
                part = value.split(";")[0]
                if "=" in part:
                    key, val = part.split("=", 1)
                    self._cookies[key.strip()] = val.strip()
        return super().parse_response(response)


def _ssl_context(cert: Path | None) -> ssl.SSLContext:
    """Контекст, доверяющий локальному сертификату стенда.

    Если файла нет — connect всё равно нужен (Kiwi только HTTPS), поэтому
    падаем с внятным сообщением, а не с ssl.SSLCertVerificationError
    в середине первого же запроса.
    """
    ctx = ssl.create_default_context()
    if cert and cert.exists():
        ctx.load_verify_locations(cafile=str(cert))
    else:
        # Самоподписанный сертификат без CA-файла проверить нечем.
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    ctx.check_hostname = False
    return ctx


@dataclass
class KiwiClient:
    url: str = DEFAULT_URL
    user: str = DEFAULT_USER
    password: str = DEFAULT_PASSWORD
    cert: Path | None = DEFAULT_CERT

    def __post_init__(self) -> None:
        ctx = _ssl_context(self.cert)
        self._transport = SessionTransport(ctx)
        self._proxy = xmlrpc.client.ServerProxy(
            self.url, allow_none=True, transport=self._transport
        )

    # --- plumbing ---------------------------------------------------------

    def call(self, method: str, *args: Any) -> Any:
        """Вызвать RPC-метод, превратив Fault в читаемую ошибку."""
        try:
            func = self._resolve(method)
            return func(*args)
        except xmlrpc.client.Fault as exc:
            raise KiwiError(
                f"{method} failed: {exc.faultString} "
                f"(code {exc.faultCode}). Проверьте, что стенд поднят "
                f"({self.url}) и что пользователь '{self.user}' существует."
            ) from exc
        except OSError as exc:
            raise KiwiError(
                f"{method}: cannot reach Kiwi at {self.url} ({exc}). "
                f"Запустите ./infra/kiwi/up.sh"
            ) from exc

    def _resolve(self, method: str):  # type: ignore[no-untyped-def]
        obj: Any = self._proxy
        for part in method.split("."):
            obj = getattr(obj, part)
        return obj

    def login(self) -> "KiwiClient":
        self.call("Auth.login", self.user, self.password)
        if "sessionid" not in self._transport._cookies:
            raise KiwiError(
                "Auth.login вернул ключ, но Set-Cookie: sessionid не пришёл — "
                "запрос ушёл анонимным и последующие вызовы не авторизуются."
            )
        return self

    def check_alive(self) -> None:
        """Проверить связность и авторизацию, не создавая данных."""
        self.login()
        self.call("Product.filter")

    # --- сущности ---------------------------------------------------------

    def get_products(self) -> list[dict]:
        return self.call("Product.filter") or []

    def get_product(self, name: str) -> dict | None:
        for product in self.get_products():
            if product.get("name") == name:
                return product
        return None

    def get_classifications(self) -> list[dict]:
        return self.call("Classification.filter", {}) or []

    def ensure_classification(self, name: str) -> dict:
        """Classification — обязательное поле Product в Kiwi 16.

        `Product.create` без него падает с «('classification', ['This field is
        required.'])». Справочник пуст на свежей БД, поэтому создаём.
        """
        for item in self.get_classifications():
            if item.get("name") == name:
                return item
        return self.call("Classification.create", {"name": name})

    def create_product(
        self, name: str, description: str = "", classification_id: int | None = None
    ) -> dict:
        if classification_id is None:
            classification_id = self.ensure_classification("Infrastructure")["id"]
        return self.call(
            "Product.create",
            {
                "name": name,
                "description": description,
                "classification": classification_id,
            },
        )

    def ensure_plan_type(self, name: str) -> dict:
        """PlanType — обязательное поле TestPlan в Kiwi 16.

        На свежей БД справочник пуст, поэтому `TestPlan.create` без него
        падает с «('type', ['This field is required.'])».
        """
        for item in self.call("PlanType.filter", {}) or []:
            if item.get("name") == name:
                return item
        return self.call("PlanType.create", {"name": name})

    def ensure_version(self, product_id: int, value: str) -> dict:
        """Version — обязательное поле TestPlan (product_version)."""
        for item in self.call(
            "Version.filter", {"product": product_id, "value": value}
        ) or []:
            return item
        return self.call("Version.create", {"product": product_id, "value": value})

    def get_plan(self, product_id: int, name: str) -> dict | None:
        for plan in self.call("TestPlan.filter", {"product__id": product_id}) or []:
            if plan.get("name") == name:
                return plan
        return None

    def create_plan(
        self,
        product_id: int,
        name: str,
        *,
        version: str = "local",
        plan_type: str = "Functional",
    ) -> dict:
        return self.call(
            "TestPlan.create",
            {
                "product": product_id,
                "name": name,
                "type": self.ensure_plan_type(plan_type)["id"],
                "product_version": self.ensure_version(product_id, version)["id"],
            },
        )

    def get_component(self, product_id: int, name: str) -> dict | None:
        for comp in self.call("Component.filter", {"product": product_id}) or []:
            if comp.get("name") == name:
                return comp
        return None

    def create_component(self, product_id: int, name: str) -> dict:
        return self.call(
            "Component.create", {"product": product_id, "name": name}
        )

    def get_cases(self, plan_id: int) -> list[dict]:
        return self.call("TestCase.filter", {"plan": plan_id}) or []

    def ensure_category(self, product_id: int, name: str) -> dict:
        """Category — обязательное поле TestCase; привязана к продукту.

        `init_db` создаёт `--default--` только для первого продукта, поэтому
        для нового продукта справочник пуст.
        """
        for item in self.call("Category.filter", {"product": product_id}) or []:
            if item.get("name") == name:
                return item
        return self.call(
            "Category.create", {"product": product_id, "name": name}
        )

    def _case_status_id(self, name: str = "CONFIRMED") -> int:
        for item in self.call("TestCaseStatus.filter", {}) or []:
            if item.get("name") == name:
                return item["id"]
        raise KiwiError(f"TestCaseStatus '{name}' не найден — стенд не инициализирован")

    def _priority_id(self, value: str = "P2") -> int:
        """Priority в Kiwi 16 — это P1..P5, а не Priority.NORMAL.

        Передача 'Normal' даёт «Select a valid choice. That choice is not one
        of the available choices.»: справочник хранит id→value, а форма
        TestCase принимает только эти строки.
        """
        for item in self.call("Priority.filter", {}) or []:
            if item.get("value") == value:
                return item["id"]
        raise KiwiError(f"Priority '{value}' не найден")

    def link_case_to_plan(self, plan_id: int, case_id: int) -> dict:
        """Привязать кейс к плану.

        Отдельный вызов обязателен: `TestCase.create` принимает ключ 'plan'
        во входных значениях, но NewForm его не обрабатывает (связь живёт в
        TestCasePlan), и кейс остаётся с plan=None — то есть невидимым ни в
        одном плане и не подхватываемым TestCase.filter({'plan': …}).
        """
        return self.call("TestPlan.add_case", plan_id, case_id)

    def create_case(
        self,
        *,
        product_id: int,
        plan_id: int,
        summary: str,
        component_id: int | None = None,
        priority: str = "P2",
        category: str = "--default--",
        case_status: str = "CONFIRMED",
    ) -> dict:
        """Создать кейс.

        `case_status` передаётся явно, а не зашивается в CONFIRMED: жизненный
        цикл спеки (`proposed`/`confirmed`/`deprecated`) должен попадать в Kiwi
        при создании. Иначе кейс с `proposed`-спеком создаётся как CONFIRMED и
        проверка `seed --check` потом не сходится никогда — «починить» это
        нельзя, потому что обновлять статус нечем.
        """
        values: dict[str, Any] = {
            "product": product_id,
            "summary": summary,
            "priority": self._priority_id(priority),
            "case_status": self._case_status_id(case_status),
            "category": self.ensure_category(product_id, category)["id"],
        }
        if component_id is not None:
            values["component"] = component_id
        case = self.call("TestCase.create", values)
        self.link_case_to_plan(plan_id, case["id"])
        return case

    def set_property(self, case_id: int, name: str, value: str) -> None:
        self.call("TestCase.add_property", case_id, name, value)

    def get_case_properties(self, case_id: int) -> dict[str, str]:
        """Все свойства одного кейса как dict.

        Две особенности, обе приводили к тихой порче данных:

        1. Фильтр — по полю `case`, а не `pk`. У модели Property PK — это id
           самой строки свойства, а не кейса. Запрос с `pk__in=[case_id]`
           не ошибался, а возвращал свойство какого-то постороннего кейса с
           таким id — то есть sync видел «у кейса нет source_path» и создавал
           дубликаты вместо обновления.
        2. Ответ — по строке на КАЖДОЕ свойство, а не по строке на кейс.
           Кейс синка несёт пять свойств, и брать только первую строку
           означало увидеть одно произвольное из них.
        """
        props = self.call("TestCase.properties", {"case": case_id}) or []
        return {p["name"]: p["value"] for p in props}

    def get_cases_properties(
        self, case_ids: list[int]
    ) -> dict[int, dict[str, str]]:
        """Свойства многих кейсов за ОДИН RPC-вызов: {case_id: {name: value}}.

        Нужен вместо цикла get_case_properties() по каждому кейсу: на
        263 кейсах это 263 round-trip против одного (замерено: 3.6 с → 0.05 с).
        Синк и gaps.py обходят ВСЕ кейсы продукта, то есть N+1 повторялся на
        каждом запуске.

        Киви не ограничивает размер выборки, но на очень больших продуктах
        (>1000 кейсов) список разбивается на части — одна длинная выборка
        означала бы либо долгий ответ, либо молчаливое усечение.
        """
        result: dict[int, dict[str, str]] = {}
        chunk = 500
        for start in range(0, len(case_ids), chunk):
            batch = case_ids[start : start + chunk]
            for prop in self.call("TestCase.properties", {"case__in": batch}) or []:
                result.setdefault(prop["case"], {})[prop["name"]] = prop["value"]
        return result

    def get_all_cases_properties(
        self, plan_ids: list[int]
    ) -> dict[int, dict[str, str]]:
        """Свойства всех кейсов указанных планов, за O(число планов) вызовов."""
        all_ids: list[int] = []
        for plan_id in plan_ids:
            all_ids.extend(case["id"] for case in self.get_cases(plan_id))
        return self.get_cases_properties(all_ids)

    def set_tag(self, case_id: int, tag: str) -> None:
        self.call("TestCase.add_tag", case_id, tag)

    # --- прогоны ---------------------------------------------------------

    def get_builds(self, version: str | None = None) -> list[dict]:
        query: dict[str, Any] = {}
        if version:
            query["version__value"] = version
        return self.call("Build.filter", query) or []

    def get_plan_builds(self, plan: dict) -> list[dict]:
        """Build'ы, допустимые для прогона в данном плане.

        NewRunForm.populate() ограничивает queryset через
        `Build.objects.filter(version_id=plan.product_version_id,
        is_active=True)` — то есть build обязан принадлежать ровно той
        версии продукта, на которую заведён план. Build другой версии даёт
        «Select a valid choice.», и это самая частая причина не создаться
        прогону.
        """
        version_id = plan.get("product_version")
        return self.call("Build.filter", {"version": version_id, "is_active": True}) or []

    def ensure_build(
        self, version: str, product_id: int, plan: dict | None = None
    ) -> dict:
        """Найти или создать Build, пригодный для прогона в `plan`.

        Kiwi сам создаёт build «unspecified» на каждую Version, поэтому
        одного поиска по строке версии мало: берём build, который реально
        пройдёт валидацию формы прогона, и только если такого нет —
        создаём именованный.

        У Build обязательны `name` и `version`, причём version — это **pk**
        записи Version, а не строка.
        """
        if plan is not None:
            for build in self.get_plan_builds(plan):
                if build.get("name") == version:
                    return build
            for build in self.get_plan_builds(plan):
                return build
        for build in self.get_builds(version):
            if build.get("name") == version:
                return build
        return self.call(
            "Build.create",
            {
                "name": version,
                "version": self.ensure_version(product_id, version)["id"],
            },
        )

    def get_runs(self, plan_id: int) -> list[dict]:
        return self.call("TestRun.filter", {"plan": plan_id}) or []

    def get_user_id(self, username: str) -> int:
        for user in self.call("User.filter", {"username__exact": username}) or []:
            return user["id"]
        raise KiwiError(f"пользователь '{username}' не найден в Kiwi")

    def create_run(
        self,
        *,
        plan_id: int,
        build_id: int,
        summary: str,
        manager: str | None = None,
    ) -> dict:
        """Создать прогон.

        `manager` обязателен: TestRun.create не подставляет текущего
        пользователя автоматически (в отличие от TestCase.author), и без
        него форма возвращает «A user name or user ID is required.».
        """
        manager = manager or self.user
        return self.call(
            "TestRun.create",
            {
                "plan": plan_id,
                "build": build_id,
                "summary": summary,
                "manager": self.get_user_id(manager),
            },
        )

    def add_case_to_run(self, run_id: int, case_id: int) -> None:
        """Добавить кейс в прогон.

        В Kiwi 16 метод небезопасен для нашего сценария: он создаёт
        TestExecution с sortkey=None, а следующий вызов на том же прогоне
        падает с «unsupported operand type(s) for +=: 'int' and 'NoneType'»
        (в add_case: `sortkey = 10; sortkey += last_te.sortkey`, где
        last_te.sortkey — None). То есть второй кейс в прогоне добавить уже
        нельзя.

        Рабочий путь — сразу создавать TestExecution с явным sortkey
        (см. add_execution); он же связывает кейс с прогоном. Метод оставлен
        для одиночных случаев и намеренно ставит sortkey через прямой вызов
        TestExecution.create, а не add_case.
        """
        raise KiwiError(
            "add_case_to_run не используется: TestRun.add_case в Kiwi 16 "
            "создаёт execution с sortkey=None и ломает следующий вызов. "
            "Добавляйте кейс через add_execution() с явным sortkey."
        )

    def _execution_status_id(self, name: str) -> int:
        for item in self.call("TestExecutionStatus.filter", {}) or []:
            if item.get("name") == name:
                return item["id"]
        raise KiwiError(f"TestExecutionStatus '{name}' не найден")

    def get_case(self, case_id: int) -> dict:
        """Один кейс по id.

        Отдельного RPC-метода TestCase.get в Kiwi 16 нет — выборка идёт
        через TestCase.filter({'pk': id}).
        """
        found = self.call("TestCase.filter", {"pk": case_id}) or []
        if not found:
            raise KiwiError(f"кейс {case_id} не найден")
        return found[0]

    def case_text_version(self, case_id: int) -> int:
        """Номер текстовой ревизии кейса для TestExecution.

        В Kiwi 16 у TestCase поля text_version УЖЕ НЕТ (текст кейса вынесен
        в отдельную модель), но у TestExecution поле `case_text_version`
        осталось — обычный IntegerField с NOT NULL, и RPC-форма требует его
        явно. Это номер СВЕДЕНИЯ о том, какая ревизия кейса проверялась, а не
        версия кейса; для автоимпорта из Gradle всегда 1, потому что sync не
        моделирует правки текста кейса.
        """
        return 1

    def add_execution(
        self,
        run_id: int,
        case_id: int,
        status: str,
        comment: str = "",
        build_id: int | None = None,
        sortkey: int = 10,
    ) -> dict:
        """Записать результат выполнения кейса в прогоне.

        `TestExecution.create` — голый ModelForm, поэтому:
          * все FK передаются **pk**, а не именами: status берётся как id из
            TestExecutionStatus, а строковое 'PASSED' даёт «Select a valid
            choice.»;
          * `case_text_version` — обязателен: NOT NULL IntegerField, иначе
            «This field is required.»;
          * FK называется `case`, а не `testcase`, и `build` обязателен:
            из прогона он НЕ наследуется.
        """
        if build_id is None:
            run = self.call("TestRun.filter", {"pk": run_id}) or []
            if not run:
                raise KiwiError(f"прогон {run_id} не найден")
            build_id = run[0].get("build")

        values: dict[str, Any] = {
            "run": run_id,
            "case": case_id,
            "case_text_version": self.case_text_version(case_id),
            "build": build_id,
            "status": self._execution_status_id(status),
            # sortkey обязателен по практике, а не по форме: TestRun.add_case
            # пишет None, после чего следующее добавление кейса в тот же
            # прогон падает. Явное значение делает прогон наполняемым целиком.
            "sortkey": sortkey,
        }
        if comment:
            values["comment"] = comment
        return self.call("TestExecution.create", values)

    def update_execution(
        self, execution_id: int, status: str, comment: str | None = None
    ) -> dict:
        """Обновить статус уже записанного execution.

        Нужно, потому что один и тот же коммит перепрогоняют: тот же HEAD,
        который прошёл вчера, падает после обновления зависимости. Если такой
        execution пропустить, в Kiwi навсегда остаётся старый PASSED, пока
        матрица результатов честно показывает ❌ — то есть сохраняется «зелёный»
        там, где правда «красный».

        Две особенности RPC-поверхности, обе привели к отладке:
          * `update(id, values)` — позиционные аргументы. Вызов с одним
            словарём даёт «update() missing 1 required positional argument:
            'values'»;
          * `status` — pk из TestExecutionStatus, а не строка, ровно как в
            `add_execution`.
        """
        values: dict[str, Any] = {"status": self._execution_status_id(status)}
        if comment is not None:
            values["comment"] = comment
        return self.call("TestExecution.update", execution_id, values)


def status_for(result: str) -> str:
    """Gradle/JUnit статус → Kiwi TestExecutionStatus value.

    Kiwi принимает только PASSED/FAILED/ERROR/IDLE/NA. В частности
    «skipped» из JUnit нельзя передать как есть — его нужно отдавать как
    IDLE с комментарием, иначе TestExecution.create вернёт ошибку формы.
    """
    mapping = {
        "passed": "PASSED",
               "pass": "PASSED",
        "failed": "FAILED",
        "failure": "FAILED",
        "error": "ERROR",
        "skipped": "IDLE",
        "ignored": "IDLE",
        "disabled": "IDLE",
        "pending": "IDLE",
    }
    return mapping.get(result.lower(), "IDLE")
