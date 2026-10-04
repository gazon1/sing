# =============================================================================
# Kiwi TCMS — local_settings override для локального стенда
# =============================================================================
# Kiwi TCMS 16.x в settings/common.py жёстко включает SECURE_SSL_REDIRECT:
#
#     SECURE_SSL_REDIRECT = "runserver" not in sys.argv and "test" not in sys.argv
#
# Т.е. в продуктовом образе (uwsgi, а не runserver) редирект на https://
# включён всегда. На локальном стенде, где TLS не настроен, это означает
# 301 на /xml-rpc/ и /json-rpc/, и клиентский код (sync.py) падает с
# ProtocolError, а не с внятной ошибкой авторизации.
#
# Kiwi читает любой *.py из каталога tcms_settings_dir — это штатный
# механизм override, documented в tcms/utils/settings.py:import_local_settings.
# Файл монтируется в docker-compose.yml; правится он, а не образ.
#
# Что здесь НЕ меняется: сам редирект включается не «выключанием
# безопасности», а тем, что стенд слушает 8080 без TLS и ходит по
# 127.0.0.1. Для любого внешнего окружения этот файл не нужен.
# =============================================================================

SECURE_SSL_REDIRECT = False

# Django 6 на settings.SECURE_SSL_REDIRECT не смотрит, но CSRF-куки
# выставляются с флагом secure при SESSION_COOKIE_SECURE. Оставляем False:
# иначе логин через /accounts/login/ не переживёт перезагрузку страницы
# на http-стенде.
SESSION_COOKIE_SECURE = False
CSRF_COOKIE_SECURE = False

# Метрики не нужны на локальном стенде и только добавляют исходящий трафик.
TCMS_TELEMETRY_ENABLED = False

# Base URL. Пока он не совпадает с реальным адресом, Kiwi показывает баннер
# "Base URL is not configured" на каждой странице, а ссылки в письмах
# и API-ответах ведут в никуда.
KIWI_BASE_URL = "http://127.0.0.1:8000"
