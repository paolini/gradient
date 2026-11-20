from pathlib import Path

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import URL


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=Path(__file__).resolve().parents[1] / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    db_host: str = "127.0.0.1"
    db_port: int = Field(default=3306, ge=1, le=65535)
    db_name: str = "gradient"
    db_user: str = "gradient"
    db_password: SecretStr

    auth_base_url: str = "http://127.0.0.1:5173"
    auth_secret: SecretStr | None = None
    google_client_id: str = ""
    google_client_secret: SecretStr = SecretStr("")
    wso2_client_id: str = ""
    wso2_client_secret: SecretStr = SecretStr("")
    wso2_authorize_url: str = "https://iam.unipi.it/oauth2/authorize"
    wso2_token_url: str = "https://iam.unipi.it/oauth2/token"
    wso2_userinfo_url: str = "https://iam.unipi.it/oauth2/userinfo"

    @model_validator(mode="after")
    def validate_auth(self):
        from urllib.parse import urlsplit
        url = urlsplit(self.auth_base_url)
        if (url.scheme not in ("http", "https") or not url.netloc or url.path not in ("", "/")
                or url.query or url.fragment or url.username or url.password):
            raise ValueError("AUTH_BASE_URL deve essere un'origine HTTP(S) senza percorso.")
        if url.scheme == "http" and url.hostname not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError("Usare HTTPS fuori dallo sviluppo locale.")
        if self.auth_secret and len(self.auth_secret.get_secret_value()) < 32:
            raise ValueError("AUTH_SECRET deve contenere almeno 32 caratteri casuali.")
        if (self.google_client_id or self.wso2_client_id) and not self.auth_secret:
            raise ValueError("Configurare AUTH_SECRET prima dei provider OAuth.")
        for endpoint in (self.wso2_authorize_url, self.wso2_token_url, self.wso2_userinfo_url):
            if not endpoint.startswith("https://"):
                raise ValueError("Gli endpoint UniPi devono usare HTTPS.")
        return self

    @property
    def database_url(self) -> URL:
        return URL.create(
            "mysql+asyncmy",
            username=self.db_user,
            password=self.db_password.get_secret_value(),
            host=self.db_host,
            port=self.db_port,
            database=self.db_name,
            query={"charset": "utf8mb4"},
        )
