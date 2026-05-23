# Script de Automação de Implantação: AndroidProtect no Coolify
# Instância de Destino: https://appbr.pro/

$ErrorActionPreference = "Stop"
Clear-Host

Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host "       🛡️  AndroidProtect - Assistente de Deploy Automático no Coolify 🛡️" -ForegroundColor Cyan
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host ""

# Configurações do Coolify
$COOLIFY_URL = "https://appbr.pro"
$API_TOKEN = "12|tE4m5PZ3goJ4rZP7JMaXcbIvA2yfiSUArW7j6ZAi0387f5a4"
$HEADERS = @{
    "Authorization" = "Bearer $API_TOKEN"
    "Content-Type"  = "application/json"
    "Accept"        = "application/json"
}

# 1. Validando Token e Obtendo Servidores
Write-Host "[*] Conectando ao painel Coolify em $COOLIFY_URL..." -ForegroundColor Yellow
try {
    $serversResponse = Invoke-RestMethod -Uri "$COOLIFY_URL/api/v1/servers" -Method Get -Headers $HEADERS
    
    if ($serversResponse.Count -eq 0) {
        Write-Error "Nenhum servidor ativo encontrado na sua instância do Coolify."
    }
    
    # Seleciona o primeiro servidor disponível (geralmente localhost / host principal)
    $targetServer = $serversResponse[0]
    $SERVER_UUID = $targetServer.uuid
    $SERVER_NAME = $targetServer.name
    Write-Host "[✓] Conexão bem-sucedida! Servidor selecionado: '$SERVER_NAME' (UUID: $SERVER_UUID)" -ForegroundColor Green
} catch {
    Write-Host "[x] Falha na autenticação ou conexão com o Coolify." -ForegroundColor Red
    Write-Host "Detalhe do erro: $_" -ForegroundColor Red
    Exit
}

# 2. Criando o Projeto AndroidProtect
Write-Host ""
Write-Host "[*] Criando projeto 'AndroidProtect' no Coolify..." -ForegroundColor Yellow

$projectBody = @{
    "name"        = "AndroidProtect"
    "description" = "Painel de Controle e Backend Anti-Roubo"
} | ConvertTo-Json

try {
    $projectResponse = Invoke-RestMethod -Uri "$COOLIFY_URL/api/v1/projects" -Method Post -Headers $HEADERS -Body $projectBody
    $PROJECT_UUID = $projectResponse.uuid
    Write-Host "[✓] Projeto 'AndroidProtect' criado com sucesso! (UUID: $PROJECT_UUID)" -ForegroundColor Green
} catch {
    # Em caso de projeto já existente, tentamos buscar a lista para pegar o UUID
    Write-Host "[i] Projeto já existente ou erro ao criar. Buscando projetos ativos..." -ForegroundColor Cyan
    try {
        $projectsList = Invoke-RestMethod -Uri "$COOLIFY_URL/api/v1/projects" -Method Get -Headers $HEADERS
        $existingProject = $projectsList | Where-Object { $_.name -eq "AndroidProtect" }
        if ($existingProject) {
            $PROJECT_UUID = $existingProject.uuid
            Write-Host "[✓] Projeto 'AndroidProtect' existente encontrado! (UUID: $PROJECT_UUID)" -ForegroundColor Green
        } else {
            Write-Error "Não foi possível recuperar o projeto."
        }
    } catch {
        Write-Host "[x] Erro fatal ao gerenciar projeto: $_" -ForegroundColor Red
        Exit
    }
}

# 3. Criando o Banco de Dados PostgreSQL no Coolify
Write-Host ""
Write-Host "[*] Criando banco de dados PostgreSQL persistente..." -ForegroundColor Yellow

$DB_NAME = "androidprotect_db"
$DB_USER = "postgres"
$DB_PASSWORD = -join ((48..57) + (97..122) + (65..90) | Get-Random -Count 16 | ForEach-Object {[char]$_}) # Senha forte aleatória
$POSTGRES_DB_NAME = "androidprotect"

$dbBody = @{
    "server_uuid"         = $SERVER_UUID
    "project_uuid"        = $PROJECT_UUID
    "environment_name"    = "production"
    "name"                = $DB_NAME
    "postgresql_database" = $POSTGRES_DB_NAME
    "postgresql_user"     = $DB_USER
    "postgresql_password" = $DB_PASSWORD
} | ConvertTo-Json

try {
    $dbResponse = Invoke-RestMethod -Uri "$COOLIFY_URL/api/v1/databases/postgresql/public" -Method Post -Headers $HEADERS -Body $dbBody
    $DB_UUID = $dbResponse.uuid
    Write-Host "[✓] PostgreSQL criado com sucesso! (UUID: $DB_UUID)" -ForegroundColor Green
} catch {
    # Em caso de banco já existente, tentamos buscar a lista
    Write-Host "[i] Banco de dados já existente ou erro ao criar. Verificando bancos cadastrados..." -ForegroundColor Cyan
    try {
        $databasesList = Invoke-RestMethod -Uri "$COOLIFY_URL/api/v1/databases" -Method Get -Headers $HEADERS
        $existingDb = $databasesList | Where-Object { $_.name -eq $DB_NAME }
        if ($existingDb) {
            $DB_UUID = $existingDb.uuid
            Write-Host "[✓] Banco PostgreSQL existente encontrado! (UUID: $DB_UUID)" -ForegroundColor Green
        } else {
            Write-Error "Não foi possível provisionar o banco."
        }
    } catch {
        Write-Host "[x] Erro ao gerenciar banco: $_" -ForegroundColor Red
        Exit
    }
}

# 4. Solicitando Dados do Repositório Git do Usuário para Criação do WebApp
Write-Host ""
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host "Para finalizar, o Coolify precisa clonar seu repositório Git." -ForegroundColor White
Write-Host "Se você já possui o projeto publicado em um Git público (ex: GitHub)," -ForegroundColor White
Write-Host "informe a URL abaixo. Caso contrário, pressione ENTER para pular" -ForegroundColor White
Write-Host "e criar o banco de dados separadamente." -ForegroundColor White
Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host ""

$gitRepo = Read-Host "URL do Repositório Git (ex: https://github.com/seuusuario/androidprotect) [Pressione ENTER para pular]"

if (-not [string]::IsNullOrEmpty($gitRepo)) {
    $gitBranch = Read-Host "Branch do Git (Padrão: main)"
    if ([string]::IsNullOrEmpty($gitBranch)) { $gitBranch = "main" }
    
    $domain = Read-Host "Domínio / FQDN para o Painel Web (ex: https://painel.appbr.pro) [Obrigatório para HTTPS]"
    
    Write-Host ""
    Write-Host "[*] Criando aplicação Web no Coolify vinculada ao Git..." -ForegroundColor Yellow
    
    $appBody = @{
        "server_uuid"      = $SERVER_UUID
        "project_uuid"     = $PROJECT_UUID
        "environment_name" = "production"
        "name"             = "androidprotect-manager"
        "git_repository"   = $gitRepo
        "git_branch"       = $gitBranch
        "build_pack"       = "docker"
        "ports_mappings"   = "8080:8080"
        "fqdn"             = $domain
    } | ConvertTo-Json
    
    try {
        $appResponse = Invoke-RestMethod -Uri "$COOLIFY_URL/api/v1/applications/public" -Method Post -Headers $HEADERS -Body $appBody
        $APP_UUID = $appResponse.uuid
        Write-Host "[✓] Aplicação Web criada com sucesso! (UUID: $APP_UUID)" -ForegroundColor Green
        
        # 5. Injetando Variáveis de Ambiente do PostgreSQL na Aplicação Web
        Write-Host ""
        Write-Host "[*] Vinculando credenciais do banco PostgreSQL às variáveis de ambiente da WebApp..." -ForegroundColor Yellow
        
        # As variáveis de ambiente que o Ktor espera:
        $envVars = @(
            @{ "key" = "DB_HOST"; "value" = "$DB_NAME" }, # O Coolify resolve o nome do container do banco como hostname automaticamente na rede interna!
            @{ "key" = "DB_PORT"; "value" = "5432" },
            @{ "key" = "DB_NAME"; "value" = "$POSTGRES_DB_NAME" },
            @{ "key" = "DB_USER"; "value" = "$DB_USER" },
            @{ "key" = "DB_PASSWORD"; "value" = "$DB_PASSWORD" }
        )
        
        foreach ($var in $envVars) {
            $envBody = @{
                "key"   = $var.key
                "value" = $var.value
                "is_build_time" = $false
            } | ConvertTo-Json
            
            # Adiciona variável via API do Coolify
            $null = Invoke-RestMethod -Uri "$COOLIFY_URL/api/v1/applications/$APP_UUID/env" -Method Post -Headers $HEADERS -Body $envBody
        }
        Write-Host "[✓] Variáveis de ambiente vinculadas com sucesso!" -ForegroundColor Green
        
        # 6. Disparando Deploy Inicial
        Write-Host ""
        Write-Host "[*] Disparando compilação e deploy inicial no Coolify..." -ForegroundColor Yellow
        $deployResponse = Invoke-RestMethod -Uri "$COOLIFY_URL/api/v1/applications/$APP_UUID/deploy" -Method Post -Headers $HEADERS
        Write-Host "[✓] Deploy iniciado! Acompanhe o progresso no console do Coolify." -ForegroundColor Green
        
    } catch {
        Write-Host "[x] Falha ao criar ou configurar aplicação: $_" -ForegroundColor Red
    }
}

# 7. Resumo de Credenciais do Banco Provisionado
Write-Host ""
Write-Host "==========================================================================" -ForegroundColor Green
Write-Host "             🎉  IMPLANTAÇÃO DE BANCO CONCLUÍDA NO COOLIFY! 🎉" -ForegroundColor Green
Write-Host "==========================================================================" -ForegroundColor Green
Write-Host "  Use estas credenciais para configurar o seu servidor Ktor manualmente," -ForegroundColor White
Write-Host "  caso decida hospedá-lo fora da rede Docker interna do Coolify:" -ForegroundColor White
Write-Host ""
Write-Host "  🔹 Host do Banco:     $DB_NAME (interno) ou o IP do seu servidor Coolify" -ForegroundColor Cyan
Write-Host "  🔹 Porta do Banco:    5432" -ForegroundColor Cyan
Write-Host "  🔹 Nome do Banco:     $POSTGRES_DB_NAME" -ForegroundColor Cyan
Write-Host "  🔹 Usuário:           $DB_USER" -ForegroundColor Cyan
Write-Host "  🔹 Senha do Banco:    $DB_PASSWORD" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Nota: Se você preencheu o Git, o deploy já está rodando e a conexão" -ForegroundColor Yellow
Write-Host "  com o banco foi configurada automaticamente via variáveis de ambiente!" -ForegroundColor Yellow
Write-Host "==========================================================================" -ForegroundColor Green
Write-Host ""
