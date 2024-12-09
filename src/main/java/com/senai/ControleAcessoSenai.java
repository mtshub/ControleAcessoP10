package com.senai;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ControleAcessoSenai {
    private static String[] matriculas = {null};
    private static final String[] cabecalhoAluno = {"ID", "RF_ID", "Nome", "Email", "Telefone", "Matrícula"};
    private static String[][] cadastroAlunos = {null};
    private static final String[] cabecalhoFuncionario = {"ID", "RF_ID", "Nome", "Email", "Telefone", "Matrícula", "Cargo"};
    private static String[][] cadastroFuncionarios = {null};
    private static final String[] cabecalhoRegistrosDeAcesso = {"RF_ID", "Nome", "Matrícula", "Hora", "Cargo"};
    private static String[][] registrosDeAcesso = {null};
    private static final Scanner read = new Scanner(System.in);
    private static final File dirBaseDeDadosGeral = new File(System.getProperty("user_home") + "\\Desktop\\Controle de Acesso - Anchieta");
    private static final File baseDeDadosAlunos = new File(dirBaseDeDadosGeral + "\\Alunos\\Base de Dados (alunos).txt");
    private static final File baseDeDadosFuncionarios = new File(dirBaseDeDadosGeral + "\\Funcionários\\Base de Dados (funcionarios).txt");
    private static final File pastaRegistrosDeAcesso = new File(dirBaseDeDadosGeral + "\\Registros de Acessos");
    private static final File arquivoRegistrosDeAcesso = new File(pastaRegistrosDeAcesso + "\\RegistrosDeAcesso.txt");
    private static final File pastaMatriculas = new File(dirBaseDeDadosGeral + "\\Matrículas");
    private static final File arquivoMatriculas = new File(pastaMatriculas + "\\matriculas.txt");
    static volatile boolean modoCadastrarIdAcesso = false;
    static String dispositivoIot = "Disp1";
    static String brokerUrl = "tcp://localhost:1883";  // Exemplo de
    static String topico = "IoTKIT1/UID";
    static CLienteMQTT conexaoMQTT;
    static ExecutorService executorIdentificarAcessos = Executors.newFixedThreadPool(4);
    static ExecutorService executorCadastroIdAcesso = Executors.newSingleThreadExecutor();

    public static void main(String[] args) {
        carregarCadastros();
        conexaoMQTT = new CLienteMQTT(brokerUrl, topico, ControleAcessoSenai::processarMensagemMQTTRecebida);
        menuMain();
        read.close();
        executorIdentificarAcessos.shutdown();
        executorCadastroIdAcesso.shutdown();
        conexaoMQTT.desconectar();
    }

    private static void cadastrarNovoIdAcesso(String novoIdAcesso) {
        System.out.print("""
                ---------------- CADASTRAR RF_ID -----------------
                
                Selecione o tipo de usuário que deseja cadastrar a tag:
                
                         1. Funcionários | 2. Alunos
                
                Escolha uma opção:\s""");
        byte opcao = read.nextByte();
        escolhaOpcaoCerta(opcao);
        int id;
        if(opcao == 1){
            exibirFuncionarios();
            System.out.print("Digite o ID do usuário que deseja associar a tag: ");
            id = read.nextInt();
            testeID(id, cadastroFuncionarios);
        } else {
            exibirAlunos();
            System.out.print("Digite o ID do usuário que deseja associar a tag: ");
            id = read.nextInt();
            testeID(id, cadastroAlunos);
        }
        conexaoMQTT.publicarMensagem(topico, dispositivoIot);

        modoCadastrarIdAcesso = true;
        System.out.println("TAG: " + novoIdAcesso + " associada com sucesso!");
        if(opcao == 1){
            cadastroFuncionarios[id][1] = novoIdAcesso;
            salvarCadastroFuncionarios();
        } else {
            cadastroAlunos[id][1] = novoIdAcesso;
            salvarCadastroAlunos();
        }

        conexaoMQTT.publicarMensagem("cadastro/disp", "CadastroConcluido");
    }

    private static void aguardarCadastroDeIdAcesso() {
        modoCadastrarIdAcesso = true;
        System.out.println("Aguardando nova tag ou cartão para associar ao usuário");
        // Usar Future para aguardar até que o cadastro de ID seja concluído
        Future<?> future = executorCadastroIdAcesso.submit(() -> {
            while (modoCadastrarIdAcesso) {
                // Loop em execução enquanto o modoCadastrarIdAcesso estiver ativo
                try {
                    Thread.sleep(100); // Evita uso excessivo de CPU
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            future.get(); // Espera até que o cadastro termine
        } catch (Exception e) {
            System.err.println("Erro ao aguardar cadastro: " + e.getMessage());
        }
    }

    private static void processarMensagemMQTTRecebida(String mensagem) {

        if (!modoCadastrarIdAcesso) {
            executorIdentificarAcessos.submit(() -> criarNovoRegistroDeAcesso(mensagem)); // Processa em thread separada
        } else {
            cadastrarNovoIdAcesso(mensagem); // Processa em thread separada
            modoCadastrarIdAcesso = false;
        }
    }

    private static void criarNovoRegistroDeAcesso(String idAcessoRecebido) {
        boolean usuarioEncontrado = false; // Variável para verificar se o usuário foi encontrado
        float verificarHora;
        String[][] novaMatrizRegistro = new String[registrosDeAcesso.length][registrosDeAcesso[0].length];
        int linhaNovoRegistro = 0;

        if (!registrosDeAcesso[0][0].isEmpty()) {//testa se o valor da primeira posição da matriz está diferente de vazia ou "".
            novaMatrizRegistro = new String[registrosDeAcesso.length + 1][registrosDeAcesso[0].length];
            linhaNovoRegistro = registrosDeAcesso.length;
            for (int linhas = 0; linhas < registrosDeAcesso.length; linhas++) {
                novaMatrizRegistro[linhas] = Arrays.copyOf(registrosDeAcesso[linhas], registrosDeAcesso[linhas].length);
            }
        }
        // Loop para percorrer a matriz e buscar o idAcesso
        for (int linhas = 1; linhas < cadastroFuncionarios.length; linhas++) { // Começa de 1 para ignorar o cabeçalho
            if (cadastroFuncionarios[linhas][1].trim().equals(idAcessoRecebido)) {
                novaMatrizRegistro[linhaNovoRegistro][0] = cadastroFuncionarios[linhas][1];
                novaMatrizRegistro[linhaNovoRegistro][1] = cadastroFuncionarios[linhas][2];
                novaMatrizRegistro[linhaNovoRegistro][2] = cadastroFuncionarios[linhas][5]; // Assume que o nome do usuário está na coluna 3
                verificarHora = Float.parseFloat(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH.mm")));
                novaMatrizRegistro[linhaNovoRegistro][3] = Float.toString(verificarHora).replaceAll("\\.", " :");//             novaMatrizRegistro[linhaNovoRegistro][3] = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                novaMatrizRegistro[linhaNovoRegistro][4] = cadastroFuncionarios[linhas][6];

                if(verificarHora <= 13.40) {
                    System.out.printf("\nAcesso liberado: %s - %s - %s - %s\n", novaMatrizRegistro[linhaNovoRegistro][0].trim(), novaMatrizRegistro[linhaNovoRegistro][1].trim(), novaMatrizRegistro[linhaNovoRegistro][3].trim(), novaMatrizRegistro[linhaNovoRegistro][4].trim());
                } else if (verificarHora > 13.40 && verificarHora< 15.40){
                    System.out.printf("\nAcesso liberado, %s atrasado.: %s - %s - %s - %s\n", novaMatrizRegistro[linhaNovoRegistro][4],novaMatrizRegistro[linhaNovoRegistro][0].trim(), novaMatrizRegistro[linhaNovoRegistro][1].trim(), novaMatrizRegistro[linhaNovoRegistro][3].trim(), novaMatrizRegistro[linhaNovoRegistro][4].trim());
                } else {
                    System.out.println("\nAcesso negado. Funcionário extremamente atrasado.");
                }
                usuarioEncontrado = true; // Marca que o usuário foi encontrado
                registrosDeAcesso = novaMatrizRegistro;
                break; // Sai do loop, pois já encontrou o usuário
            }
        }

        for (int linhas = 1; linhas < cadastroAlunos.length; linhas++) { // Começa de 1 para ignorar o cabeçalho
            if (cadastroAlunos[linhas][1].trim().equals(idAcessoRecebido)) {
                novaMatrizRegistro[linhaNovoRegistro][0] = cadastroAlunos[linhas][1];
                novaMatrizRegistro[linhaNovoRegistro][1] = cadastroAlunos[linhas][2];
                novaMatrizRegistro[linhaNovoRegistro][2] = cadastroAlunos[linhas][5]; // Assume que o nome do usuário está na coluna 3
                verificarHora = Float.parseFloat(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH.mm")));
                novaMatrizRegistro[linhaNovoRegistro][3] = Float.toString(verificarHora).replaceAll("\\.", " :");
                novaMatrizRegistro[linhaNovoRegistro][4] = "Aluno";
                if(verificarHora <= 13.40) {
                    System.out.printf("\nAcesso liberado: %s - %s - %s - %s\n", novaMatrizRegistro[linhaNovoRegistro][0].trim(), novaMatrizRegistro[linhaNovoRegistro][1].trim(), novaMatrizRegistro[linhaNovoRegistro][3].trim(), novaMatrizRegistro[linhaNovoRegistro][4].trim());
                } else if (verificarHora > 13.40 && verificarHora< 15.40){
                    System.out.printf("\nAcesso liberado, %s atrasado.: %s - %s - %s - %s\n", novaMatrizRegistro[linhaNovoRegistro][4],novaMatrizRegistro[linhaNovoRegistro][0].trim(), novaMatrizRegistro[linhaNovoRegistro][1].trim(), novaMatrizRegistro[linhaNovoRegistro][3].trim(), novaMatrizRegistro[linhaNovoRegistro][4].trim());
                } else {
                    System.out.println("\nAcesso negado. Aluno extremamente atrasado.");
                }
                usuarioEncontrado = true; // Marca que o usuário foi encontrado
                registrosDeAcesso = novaMatrizRegistro;
                break; // Sai do loop, pois já encontrou o usuário
            }
        }
        // Se não encontrou o usuário, imprime uma mensagem
        if (!usuarioEncontrado) {
            System.out.println("Id de Acesso " + idAcessoRecebido + " não cadastrado.");
        }

        salvarRegistrosDeAcesso();
    }

    private static void menuMain() {
        byte opcao;
        do {
            System.out.print("""
                    --------------------- HOME ----------------------
                    |           1- Cadastrar Novo Usuário.          |
                    |           2- Atualizar Usuário.               |
                    |           3- Deletar Usuário.                 |
                    |           4- Exibir Cadastro.                 |
                    |           5- Buscar Por Usuário.              |
                    |           6- Cadastrar Nova Tag               |
                    |           7- Limpar Tela.                     |
                    |-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-|
                    |           0- Finalizar Programa.              |
                    -------------------------------------------------
                    Escolha uma opção:\s""");
            opcao = read.nextByte();
            read.nextLine();
            System.out.println();
            do {
                switch (opcao) {
                    case 1:
                        cadastrarNovoUsuario();
                        break;
                    case 2:
                        atualizarUsuario();
                        break;
                    case 3:
                        deletarUsuario();
                        break;
                    case 4:
                        exibirCadastro();
                        break;
                    case 5:
                        buscarUsuarios();
                        break;
                    case 6:
                        aguardarCadastroDeIdAcesso();
                        break;
                    case 7:
                        limparTela();
                        break;
                    case 0:
                        System.out.println("O programa se encerrará em breve.\n");
                        break;
                    default:
                        System.out.println("Opção inválida. Tente novamente.");
                        System.out.print("Escolha uma opção: ");
                        opcao = read.nextByte();
                        read.nextLine();
                        System.out.println();
                }
            } while (opcao < 0 || opcao > 7);
        } while (opcao != 0);
        System.out.print("Salvando informações.");
        try {
            for (byte i = 0; i < 5; i++) {
                Thread.sleep(350);
                System.out.print(".");
            }
            Thread.sleep(700);
            System.out.println("\nInformações salvas! Programa encerrado.");
        } catch (Exception ignored) {
        }
    }

    private static void buscarUsuarios() {
        System.out.println("\n---------------- BUSCAR USUÁRIOS ----------------");
        System.out.println("""
                Selecione o cadastro que deseja buscar o usuário:
                
                \t\t1. Funcionários | 2. Alunos
                """);
        System.out.print("Escolha uma opção: ");
        byte opcao = read.nextByte();
        escolhaOpcaoCerta(opcao);
        read.nextLine();
        byte filtro;
        System.out.println();
        if (opcao == 1) {
            System.out.print("""
                    Deseja filtrar os funcionários por:
                    
                            1. Nome | 2. Matrícula
                    
                    Escolha um dos filtros:\s""");
            filtro = read.nextByte();
            escolhaOpcaoCerta(filtro);
            read.nextLine();
            System.out.println();
            if (filtro == 1) {
                filtrarFuncionarioPorNome();
            } else {
                filtrarFuncionarioPorMatricula();
            }
        } else {
            System.out.print("""
                    Deseja filtrar os alunos por:
                    
                            1. Nome | 2. Matrícula
                    
                    Escolha um dos filtros:\s""");
            filtro = read.nextByte();
            escolhaOpcaoCerta(filtro);
            read.nextLine();
            System.out.println();
            if (filtro == 1) {
                filtrarAlunoPorNome();
            } else {
                filtrarAlunoPorMatricula();
            }
        }
    }

    private static void escolhaOpcaoCerta(byte opcao) {
        while (opcao != 1 && opcao != 2) {
            System.out.println("Opção inválida. Tente novamente.");
            System.out.print("Escolha uma opção: ");
            opcao = read.nextByte();
        }
    }

    private static void exibirCadastro() {
        System.out.print("""
                ---------------- EXIBIR CADASTRO -----------------
                
                Selecione o cadastro que deseja exibir:
                
                         1. Funcionários | 2. Alunos
                
                Escolha uma opção:\s""");
        byte tipoCadastro = read.nextByte();
        escolhaOpcaoCerta(tipoCadastro);
        read.nextLine();
        if (tipoCadastro == 1) {
            exibirFuncionarios();
        } else {
            exibirAlunos();
        }
    }

    private static void exibirAlunos() {
        System.out.println("\n---------------------------------------------------------- ALUNOS ----------------------------------------------------------");
        informacoesCadastro(cadastroAlunos);
        System.out.println("----------------------------------------------------------------------------------------------------------------------------\n");
    }


    private static void exibirFuncionarios() {
        System.out.println("\n--------------------------------------------------------------- FUNCIONÁRIOS ---------------------------------------------------------------");
        informacoesCadastro(cadastroFuncionarios);
        System.out.println("--------------------------------------------------------------------------------------------------------------------------------------------\n");
    }

    private static void informacoesCadastro(String[][] cadastro) {
        if (cadastroNulo(cadastro)) {
            return;
        }
        for (String[] usuario : cadastro) {
            for (byte secao = 0; secao < cadastro[0].length; secao++) {
                System.out.print(formatarCadastro(usuario[secao], secao));
            }
            if (cadastro[0].length == cadastroAlunos[0].length) {
                System.out.println();
            }
        }
    }

    private static void cadastrarNovoUsuario() {
        System.out.println("\n----------------------------- CADASTRAR -----------------------------\n");
        System.out.print("""
                Selecione o tipo de usuário que deseja cadastrar:
                
                            1. Funcionário | 2. Aluno
                
                Escolha uma opção:\s""");
        byte tipoUsuario = read.nextByte();
        escolhaOpcaoCerta(tipoUsuario);
        if (tipoUsuario == 1) {
            cadastrarFuncionarios();
        } else {
            cadastrarAlunos();
        }
    }

    private static void cadastrarFuncionarios() {
        System.out.println("\n------------------------------ CADASTRAR FUNCIONÁRIOS ------------------------------\n");
        cadastrar(cadastroFuncionarios);
        exibirFuncionarios();
        salvarCadastroFuncionarios();
    }

    private static void cadastrarAlunos() {
        System.out.println("\n------------------------------ CADASTRAR ALUNOS ------------------------------\n");
        cadastrar(cadastroAlunos);
        exibirAlunos();
        salvarCadastroAlunos();
    }

    private static void cadastrar(String[][] cadastro) {
        System.out.print("Digite quantos usuários deseja cadastrar: ");
        int qtdUsuarios = read.nextInt();
        read.nextLine();
        byte cargo, qtdAQV = 0, qtdCoord = 0;

        String[][] recebeCadastro = new String[cadastro.length + qtdUsuarios][cadastro[0].length];
        recebeCadastro[0] = cadastro[0];

        for (int usuario = 0; usuario < cadastro.length; usuario++) {
            recebeCadastro[usuario] = Arrays.copyOf(cadastro[usuario], cadastro[0].length);
            if (cadastro[0].length == cadastroFuncionarios[0].length) {
                if (recebeCadastro[usuario][6].equalsIgnoreCase("AQV")) {
                    qtdAQV++;
                } else if (recebeCadastro[usuario][6].equalsIgnoreCase("Coordenador(a)")) {
                    qtdCoord++;
                }
            }
        }

        System.out.println();
        for (int usuario = cadastro.length; usuario < recebeCadastro.length; usuario++) {
            System.out.println("ID: " + usuario);
            recebeCadastro[usuario][0] = String.valueOf(usuario);
            recebeCadastro[usuario][1] = "-";
            for (byte secao = 2; secao < cadastro[0].length; secao++) {
                if (cadastro[0].length == cadastroAlunos[0].length) {
                    if (cadastro[0][secao].equalsIgnoreCase("Matrícula")) {
                        System.out.print(recebeCadastro[0][secao] + ": ");
                        recebeCadastro[usuario][secao] = testeMatricula(read.nextLine());
                    } else {
                        System.out.print(recebeCadastro[0][secao] + ": ");
                        recebeCadastro[usuario][secao] = read.nextLine();
                        informacaoNula(usuario, secao, recebeCadastro);
                    }
                } else {
                    if (secao != cadastro[0].length - 1) {
                        if (!cadastro[0][secao].equalsIgnoreCase("Matrícula")) {
                            System.out.print(recebeCadastro[0][secao] + ": ");
                            recebeCadastro[usuario][secao] = read.nextLine();
                            informacaoNula(usuario, secao, recebeCadastro);
                        } else {
                            System.out.print(recebeCadastro[0][secao] + ": ");
                            recebeCadastro[usuario][secao] = testeMatricula(read.nextLine());
                        }
                    } else if (qtdAQV == 0 && qtdCoord < 2) {
                        System.out.print("""
                                Cargo:
                                    1- AQV
                                    2- Coordenador(a)
                                    3- Professor(a)
                                    4- Aux. ADM
                                    5- Segurança
                                    6- Faxineiro(a)
                                Escolha um cargo:\s""");
                        cargo = read.nextByte();
                        while (cargo < 1 || cargo > 6) {
                            System.out.println("Opção inválida.");
                            System.out.print("Escolha um cargo: ");
                            cargo = read.nextByte();
                        }
                        read.nextLine();
                        switch (cargo) {
                            case 1 -> recebeCadastro[usuario][secao] = "AQV";
                            case 2 -> recebeCadastro[usuario][secao] = "Coordenador(a)";
                            case 3 -> recebeCadastro[usuario][secao] = "Professor(a)";
                            case 4 -> recebeCadastro[usuario][secao] = "Aux. ADM";
                            case 5 -> recebeCadastro[usuario][secao] = "Segurança";
                            case 6 -> recebeCadastro[usuario][secao] = "Faxineiro(a)";
                        }
                        qtdAQV = cargo == 1 ? ++qtdAQV : qtdAQV;
                        qtdCoord = cargo == 2 ? ++qtdCoord : qtdCoord;
                    } else if (qtdAQV == 1 && qtdCoord < 2) {
                        System.out.print("""
                                Cargo:
                                    1- Coordenador(a)
                                    2- Professor(a)
                                    3- Aux. ADM
                                    4- Segurança
                                    5- Faxineiro(a)
                                Escolha um cargo:\s""");
                        cargo = read.nextByte();
                        while (cargo < 1 || cargo > 5) {
                            System.out.println("Opção inválida.");
                            System.out.print("Escolha um cargo: ");
                            cargo = read.nextByte();
                        }
                        read.nextLine();
                        switch (cargo) {
                            case 1 -> recebeCadastro[usuario][secao] = "Coordenador(a)";
                            case 2 -> recebeCadastro[usuario][secao] = "Professor(a)";
                            case 3 -> recebeCadastro[usuario][secao] = "Aux. ADM";
                            case 4 -> recebeCadastro[usuario][secao] = "Segurança";
                            case 5 -> recebeCadastro[usuario][secao] = "Faxineiro(a)";
                        }
                        qtdCoord = cargo == 1 ? ++qtdCoord : qtdCoord;
                    } else if (qtdAQV == 0 && qtdCoord == 2) {
                        System.out.print("""
                                Cargo:
                                    1- AQV
                                    2- Professor(a)
                                    3- Aux. ADM
                                    4- Segurança
                                    5- Faxineiro(a)
                                Escolha um cargo:\s""");
                        cargo = read.nextByte();
                        while (cargo < 1 || cargo > 5) {
                            System.out.println("Opção inválida.");
                            System.out.print("Escolha um cargo: ");
                            cargo = read.nextByte();
                        }
                        read.nextLine();
                        switch (cargo) {
                            case 1 -> recebeCadastro[usuario][secao] = "AQV";
                            case 2 -> recebeCadastro[usuario][secao] = "Professor(a)";
                            case 3 -> recebeCadastro[usuario][secao] = "Aux. ADM";
                            case 4 -> recebeCadastro[usuario][secao] = "Segurança";
                            case 5 -> recebeCadastro[usuario][secao] = "Faxineiro(a)";
                        }
                        qtdAQV = cargo == 1 ? ++qtdAQV : qtdAQV;
                    } else {
                        System.out.print("""
                                Cargo:
                                    1- Professor(a)
                                    2- Aux. ADM
                                    3- Segurança
                                    4- Faxineiro(a)
                                Escolha um cargo:\s""");
                        cargo = read.nextByte();
                        while (cargo < 1 || cargo > 4) {
                            System.out.println("Opção inválida.");
                            System.out.print("Escolha um cargo: ");
                            cargo = read.nextByte();
                        }
                        read.nextLine();
                        switch (cargo) {
                            case 1 -> recebeCadastro[usuario][secao] = "Professor(a)";
                            case 2 -> recebeCadastro[usuario][secao] = "Aux. ADM";
                            case 3 -> recebeCadastro[usuario][secao] = "Segurança";
                            case 4 -> recebeCadastro[usuario][secao] = "Faxineiro(a)";
                        }
                    }
                }
            }
            System.out.println();
        }

        if (recebeCadastro[0].length == cadastroFuncionarios[0].length) {
            cadastroFuncionarios = recebeCadastro;
        } else {
            cadastroAlunos = recebeCadastro;
        }

        System.out.println("Cadastro realizado com sucesso!");
    }

    private static void informacaoNula(int pessoa, byte secao, String[][] cadastro) {
        while (cadastro[pessoa][secao].isBlank()) {
            System.out.println("A informação deve ser preenchida. Por favor, tente novamente.");
            System.out.print(cadastro[0][secao] + ": ");
            cadastro[pessoa][secao] = read.nextLine();
        }
    }

    private static boolean cadastroNulo(String[][] cadastro) {
        boolean nulo = cadastro.length < 2;
        if (nulo) {
            if (cadastro[0].length == cadastroFuncionarios[0].length) {
                System.out.println("\n\t\t\t\t\t\tO cadastro ainda não contém informações.\n");
            } else {
                System.out.println("\n\t\t\t\tO cadastro ainda não contém informações.\n");
            }
            return true;
        } else {
            return false;
        }
    }

    private static String testeMatricula(String matricula) {
        matricula = matricula.replaceAll("\\s", "");

        String[] adicionarMatricula = new String[matriculas.length + 1];

        for (int linhaMatricula = 0; linhaMatricula < matriculas.length; linhaMatricula++) {
           adicionarMatricula[linhaMatricula] = matriculas[linhaMatricula];
        }

        while(matricula.length() != 8 || !matricula.matches("\\d{8}")){
            System.out.println("A matrícula deve conter exatamente 8 números.");
            System.out.print("Digite a matrícula: ");
            matricula = read.nextLine();
        }

        do{
            if(!matricula.matches("\\d{8}") || matricula.length() !=8) {
                System.out.println("\nA matrícula deve conter exatamente 8 números.");
                System.out.print("Digite a matrícula: ");
                matricula = read.nextLine();
            }
            for (int linhaMatricula = 0; linhaMatricula < matriculas.length; linhaMatricula++) {
                if(matricula.equals(matriculas[linhaMatricula])){
                    System.out.println("\nOutro usuário com esta matrícula. Tente novamente.");
                    System.out.print("Matrícula: ");
                    matricula = read.nextLine();
                    break;
                } else if (linhaMatricula == matriculas.length - 1 && !matricula.equals(matriculas[linhaMatricula])) {
                    adicionarMatricula[matriculas.length] = matricula;
                    break;
                }
            }
        } while (matricula.length() != 8 || !matricula.matches("\\d{8}") || adicionarMatricula[adicionarMatricula.length - 1] == null);

        matriculas = adicionarMatricula;
        salvarMatriculas();
        return matricula;
    }

    private static void formatarCabecalho(String[][] cadastro) {
        StringBuilder cabecalhoCadastro = new StringBuilder();
        for (byte cabecalho = 0; cabecalho < cadastro[0].length; cabecalho++) {
            cabecalhoCadastro.append(formatarCadastro(cadastro[0][cabecalho], cabecalho));
        }
        if (cadastro[0].length == cadastroAlunos[0].length) {
            cabecalhoCadastro.append("\n");
        }
        System.out.print(cabecalhoCadastro);
    }

    private static String formatarCadastro(String usuario, byte secao) {
        return switch (secao) {
            case 0 -> String.format("%-5.5s |", usuario);
            case 1 -> String.format(" %-10.10s |", usuario);
            case 2, 3 -> String.format(" %-30.30s |", usuario);
            case 4, 5 -> String.format(" %-16.16s |", usuario);
            case 6 -> String.format(" %s%n", usuario);
            default -> "";
        };
    }

    private static String formatarRegistrosDeAcesso(String usuario, byte secao) {
        return switch (secao) {
            case 0 -> String.format("%-10.10s |", usuario);
            case 1, 2 -> String.format(" %-30.30s |", usuario);
            case 3 -> String.format("%-16.16s |", usuario);
            case 4 -> String.format(" %s%n", usuario);
            default -> "";
        };
    }

    private static void buscarPorID(int id, String[][] cadastro) {
        for (byte secao = 0; secao < cadastro[0].length; secao++) {
            System.out.print(formatarCadastro(cadastro[id][secao], secao));
        }
    }

    private static void filtrarAlunoPorMatricula() {
        filtrarPorMatricula(cadastroAlunos);
    }

    private static void filtrarFuncionarioPorMatricula() {
        filtrarPorMatricula(cadastroFuncionarios);
    }

    private static void filtrarFuncionarioPorNome() {
        filtrarPorNome(cadastroFuncionarios);
    }

    private static void filtrarAlunoPorNome() {
        filtrarPorNome(cadastroAlunos);
    }

    private static void filtrarPorNome(String[][] cadastro) {
        if (cadastroNulo(cadastro)) {
            return;
        }
        System.out.print("Digite o nome que deseja buscar: ");
        String nome = read.nextLine().trim();
        String primeiroNome = nome.split(" ")[0];
        boolean usuarioFoiEncontrado = false;

        StringBuilder usuariosEncontrados = new StringBuilder();

        for (int usuario = 1; usuario < cadastro.length; usuario++) {
            if (nome.split(" ").length > 1) {
                if (nome.equalsIgnoreCase(cadastro[usuario][1])) {
                    for (byte secao = 0; secao < cadastro[0].length; secao++) {
                        usuariosEncontrados.append(formatarCadastro(cadastro[usuario][secao], secao));
                    }
                    usuarioFoiEncontrado = true;
                }
            } else {
                if (primeiroNome.equalsIgnoreCase(cadastro[usuario][1].split(" ")[0])) {
                    for (byte secao = 0; secao < cadastro[0].length; secao++) {
                        usuariosEncontrados.append(formatarCadastro(cadastro[usuario][secao], secao));
                    }
                    usuarioFoiEncontrado = true;
                }
            }
        }

        if (usuarioFoiEncontrado) {
            System.out.println("\nUsuários encontrados!");
            formatarCabecalho(cadastro);
            System.out.println(usuariosEncontrados);
            System.out.println();
        } else {
            System.out.println("Nenhum usuário encontrado com este nome.\n");
        }
    }

    private static void filtrarPorMatricula(String[][] cadastro) {
        if (cadastroNulo(cadastro)) {
            return;
        }
        System.out.print("Digite a matrícula a ser buscada: ");
        String matricula = read.nextLine();
        testeMatricula(matricula);
        boolean usuarioEncontrado = false;
        int idUsuario = 0;

        for (int usuario = 1; usuario < cadastro.length; usuario++) {
            if (matricula.equalsIgnoreCase(cadastro[usuario][4])) {
                usuarioEncontrado = true;
                idUsuario = usuario;
                break;
            }
        }

        if (usuarioEncontrado) {
            System.out.println("\nUsuário encontrado!");
            formatarCabecalho(cadastro);
            buscarPorID(idUsuario, cadastro);
            System.out.println();
        } else {
            System.out.println("Matrícula não encontrada no sistema.\n");
        }

    }

    private static void salvarCadastroFuncionarios() {
        try (BufferedWriter buffWriter = new BufferedWriter(new FileWriter(baseDeDadosFuncionarios))) {
            StringBuilder recebeFuncionarios = new StringBuilder();

            for (String[] funcionario : cadastroFuncionarios) {
                for (byte secao = 0; secao < cadastroFuncionarios[0].length; secao++) {
                    recebeFuncionarios.append(formatarCadastro(funcionario[secao], secao));
                }
            }
            buffWriter.write(recebeFuncionarios.toString());
        } catch (Exception e) {
            System.out.println("Erro ao salvar automáticamente: Cadastro Funcionários.");
        }
    }

    private static void salvarCadastroAlunos() {
        try (BufferedWriter buffWriter = new BufferedWriter(new FileWriter(baseDeDadosAlunos))) {
            StringBuilder recebeAlunos = new StringBuilder();

            for (String[] aluno : cadastroAlunos) {
                for (byte secao = 0; secao < cadastroAlunos[0].length; secao++) {
                    recebeAlunos.append(formatarCadastro(aluno[secao], secao));
                    if (secao == cadastroAlunos[0].length - 1) {
                        recebeAlunos.append("\n");
                    }
                }
            }
            buffWriter.write(recebeAlunos.toString());
        } catch (Exception e) {
            System.out.println("Erro ao salvar automáticamente: Cadastro Alunos.");
        }
    }

    private static void carregarCadastros() {
        carregarCadastroAlunos();
        carregarCadastroFuncionarios();
        carregarRegistrosDeAcesso();
        carregarMatriculas();
    }

    private static void carregarCadastroAlunos() {
        if (!baseDeDadosAlunos.exists()) {
            System.out.println("\nNenhuma informação encontrada: Base de dados (alunos).");
            File dirBaseDeDadosAlunos = new File(dirBaseDeDadosGeral + "\\Alunos");
            if (!dirBaseDeDadosAlunos.exists()) {
                dirBaseDeDadosAlunos.mkdirs();
            }
            cadastroAlunos[0] = cabecalhoAluno;
            return;
        }
        try (BufferedReader buffReader = new BufferedReader(new FileReader(baseDeDadosAlunos))) {
            StringBuilder recebeBddAlunos = new StringBuilder();
            String aluno;
            while ((aluno = buffReader.readLine()) != null) {
                recebeBddAlunos.append(aluno).append("\n");
            }

            String[] qtdAlunos = recebeBddAlunos.toString().split("\n");
            cadastroAlunos = new String[qtdAlunos.length][qtdAlunos[0].split("\\|").length];

            for (int linhaAluno = 0; linhaAluno < qtdAlunos.length; linhaAluno++) {
                cadastroAlunos[linhaAluno] = qtdAlunos[linhaAluno].trim().split("\\| ");
            }

            System.out.println("\nCadastro de alunos carregado com sucesso!");
        } catch (Exception e) {
            System.out.println("""
                    
                    Erro ao carregar a base de dados de ALUNOS. Qualquer nova alteração pode sobreescrever os dados anteriores.
                    Reinicie o programa e tente novamente.
                    
                    """);
        }
        cadastroAlunos[0] = cabecalhoAluno;
    }

    private static void deletarUsuario() {
        System.out.println("------------------------------ DELETAR USUÁRIO ------------------------------");
        System.out.print("""
                Selecione o tipo de usuário que deseja deletar:
                
                        1. Funcionário | 2. Aluno
                
                Escolha uma opção:\s""");
        byte tipoUsuario = read.nextByte();
        escolhaOpcaoCerta(tipoUsuario);
        if (tipoUsuario == 1) {
            deletarFuncionario();
        } else {
            deletarAluno();
        }
    }

    private static void deletarAluno() {
        System.out.println("\n-------------------------------- ALUNOS --------------------------------");
        deletar(cadastroAlunos);
        exibirAlunos();
        salvarCadastroAlunos();
    }

    private static void deletarFuncionario() {
        System.out.println("\n------------------------------------- FUNCIONÁRIOS -------------------------------------");
        deletar(cadastroFuncionarios);
        exibirFuncionarios();
        salvarCadastroFuncionarios();
    }

    private static void carregarCadastroFuncionarios() {
        if (!baseDeDadosFuncionarios.exists()) {
            System.out.println("\nNenhuma informação encontrada: Base de dados (funcionários).\n");
            File dirBaseDeDadosFuncionarios = new File(dirBaseDeDadosGeral + "\\Funcionários");
            if (!dirBaseDeDadosFuncionarios.exists()) {
                dirBaseDeDadosFuncionarios.mkdirs();
            }
            cadastroFuncionarios[0] = cabecalhoFuncionario;
            return;
        }
        try (BufferedReader buffReader = new BufferedReader(new FileReader(baseDeDadosFuncionarios))) {
            StringBuilder recebeBddFuncionarios = new StringBuilder();
            String funcionario;
            while ((funcionario = buffReader.readLine()) != null) {
                recebeBddFuncionarios.append(funcionario).append("\n");
            }

            String[] qtdFuncionarios = recebeBddFuncionarios.toString().split("\n");
            cadastroFuncionarios = new String[qtdFuncionarios.length][qtdFuncionarios[0].split("\\|").length];

            for (int linhaFuncionario = 0; linhaFuncionario < qtdFuncionarios.length; linhaFuncionario++) {
                cadastroFuncionarios[linhaFuncionario] = qtdFuncionarios[linhaFuncionario].trim().split("\\| ");
            }

            System.out.println("\nCadastro de funcionários carregado com sucesso!\n");
        } catch (Exception e) {
            System.out.println("""
                    
                    Erro ao carregar a base de dados de FUNCIONÁRIOS. Qualquer nova alteração pode sobreescrever os dados anteriores.
                    Reinicie o programa e tente novamente.
                    
                    """);
        }
        cadastroFuncionarios[0] = cabecalhoFuncionario;
    }

    private static void limparTela() {
        try {
            new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
        } catch (Exception ignored) {
        }
    }

    private static void deletar(String[][] cadastro) {
        if (cadastroNulo(cadastro)) {
            return;
        }
        informacoesCadastro(cadastro);
        System.out.println();
        System.out.print("Digite o ID do usuário a ser deletado: ");
        int id = read.nextInt();
        testeID(id, cadastro);

        System.out.println("\nVocê tem certeza que deseja excluir o usuário? A exclusão não pode ser desfeita.");
        System.out.print("Confirmar exclusão (s/n): ");
        char confirmarExclusao = Character.toLowerCase(read.next().charAt(0));
        confirmarMudanca(confirmarExclusao);
        if (confirmarExclusao == 'n') {
            System.out.println("\nExclusão cancelada.\n");
            return;
        }

        for (int linhaMatricula = 0; linhaMatricula < matriculas.length; linhaMatricula++) {
            if(cadastro[id][5].trim().equals(matriculas[linhaMatricula])){
                matriculas[linhaMatricula] = null;
                salvarMatriculas();
                break;
            }
        }

        String[][] cadastroNovo = new String[cadastro.length - 1][cadastro[0].length];
        cadastroNovo[0] = cadastro[0];
        for (int usuario = 1, idNovo = 1; usuario < cadastro.length; usuario++) {
            if (usuario != id) {
                cadastroNovo[idNovo] = cadastro[usuario];
                cadastroNovo[idNovo][0] = String.valueOf(idNovo);
                idNovo++;
            }
        }
        System.out.println("\nUsuário deletado com sucesso! Cadastro atualizado:");
        if (cadastroNovo[0].length == cadastroFuncionarios[0].length) {
            cadastroFuncionarios = cadastroNovo;
        } else {
            cadastroAlunos = cadastroNovo;
        }
    }

    private static void testeID(int id, String[][] cadastro) {
        while (id <= 0 || id >= cadastro.length) {
            System.out.println("\nID inválido. Tente novamente.");
            System.out.print("Insira o ID do usuário: ");
            id = read.nextInt();
        }
    }

    private static void confirmarMudanca(char confirmar) {
        while (confirmar != 's' && confirmar != 'n') {
            System.out.println("Opção inválida. Tente novamente.");
            System.out.print("Deseja confirmar? (s/n)");
            confirmar = Character.toLowerCase(read.next().charAt(0));
        }
    }

    private static void atualizarUsuario() {
        System.out.println("----------------------------- ATUALIZAR USUÁRIO -----------------------------");
        System.out.print("""
                Selecione o tipo de usuário que deseja atualizar:
                
                        1. Funcionário | 2. Aluno
                
                Escolha uma opção:\s""");
        byte tipoUsuario = read.nextByte();
        escolhaOpcaoCerta(tipoUsuario);
        if (tipoUsuario == 1) {
            atualizarFuncionario();
        } else {
            atualizarAluno();
        }
    }

    private static void atualizarAluno() {
        if (cadastroNulo(cadastroAlunos)) {
            return;
        }
        System.out.println("\n----------------------------- ATUALIZAR ALUNO -----------------------------");
        atualizar(cadastroAlunos);
        salvarCadastroAlunos();
    }

    private static void atualizarFuncionario() {
        if (cadastroNulo(cadastroFuncionarios)) {
            return;
        }
        System.out.println("\n----------------------------- ATUALIZAR FUNCIONÁRIO -----------------------------");
        atualizar(cadastroFuncionarios);
        salvarCadastroFuncionarios();
    }

    private static void atualizar(String[][] cadastro) {
        if (cadastroNulo(cadastro)) {
            return;
        }
        informacoesCadastro(cadastro);
        System.out.println();
        System.out.print("Digite o ID do usuário a ser atualizado: ");
        int id = read.nextInt();
        testeID(id, cadastro);
        System.out.println("\nUsuário com ID: " + id);
        formatarCabecalho(cadastro);
        buscarPorID(id, cadastro);
        read.nextLine();

        System.out.println("\nVocê tem certeza que deseja atualizar o usuário?");
        System.out.print("Confirmar atualização (s/n): ");
        char confirmarAtualizacao = Character.toLowerCase(read.next().charAt(0));
        confirmarMudanca(confirmarAtualizacao);
        if (confirmarAtualizacao == 'n') {
            System.out.println("\nAtualização cancelada.\n");
            return;
        }
        System.out.println();

        byte opcao;
        char continuarAlterando;

        do {
            if (cadastro[0].length == cadastroFuncionarios[0].length) {
                System.out.print("""
                        Selecione o que deseja alterar:
                        --------------------------------
                        |        1- Nome               |
                        |        2- Email              |
                        |        3- Telefone           |
                        |        4- Cargo              |
                        --------------------------------
                        Escolha uma opção:\s""");
                opcao = read.nextByte();
                while (opcao < 1 || opcao > 4) {
                    System.out.println("Opção inválida. Tente novamente.");
                    System.out.print("Escolha uma opção: ");
                    opcao = read.nextByte();
                }
                System.out.println();
                read.nextLine();
            } else {
                System.out.print("""
                        Selecione o que deseja alterar:
                        --------------------------------
                        |        1- Nome               |
                        |        2- Email              |
                        |        3- Telefone           |
                        --------------------------------
                        Escolha uma opção:\s""");
                opcao = read.nextByte();
                while (opcao < 1 || opcao > 3) {
                    System.out.println("Opção inválida. Tente novamente.");
                    System.out.print("Escolha uma opção: ");
                    opcao = read.nextByte();
                }
                read.nextLine();
                System.out.println();
            }

            System.out.println("Faça a alteração: ");
            if (opcao != 4) {
                opcao += 1;
                System.out.print(cadastro[0][opcao] + ": ");
                cadastro[id][opcao] = read.nextLine();
                informacaoNula(id, opcao, cadastro);
            } else {
                byte qtdAQV = 0, qtdCoord = 0;
                for (int usuario = 1; usuario < cadastro.length; usuario++) {
                    if (cadastro[usuario][6].equalsIgnoreCase("AQV")) {
                        qtdAQV++;
                    } else if (cadastro[usuario][6].equalsIgnoreCase("Coordenador(a)")) {
                        qtdCoord++;
                    }
                }

                System.out.print("""
                        Selecione o novo cargo:
                        ---------------------------
                        |     1- AQV              |
                        |     2- Coordenador(a)   |
                        |     3- Professor(a)     |
                        |     4- Aux. ADM         |
                        |     5- Segurança        |
                        |     6- Faxineiro(a)     |
                        ---------------------------
                        Escolha um cargo:\s""");
                byte cargo = read.nextByte();

                while (cargo < 1 || cargo > 6) {
                    System.out.println("Opção inválida. Tente novamente.");
                    System.out.print("Escolha um cargo: ");
                    cargo = read.nextByte();
                    System.out.println();
                }

                String[] cargos = {"", "AQV", "Coordenador(a)", "Professor(a)", "Aux. ADM", "Segurança", "Faxineiro(a)"};
                boolean cargoIgual = cargos[cargo].trim().equalsIgnoreCase(cadastro[id][5].trim());

                System.out.println();
                while (cargoIgual) {
                    System.out.println("A alteração exige que o usuário não mantenha o mesmo cargo.");
                    System.out.print("Escolha um cargo: ");
                    cargo = read.nextByte();

                    while (cargo < 1 || cargo > 6) {
                        System.out.println("Opção inválida. Tente novamente.");
                        System.out.print("Escolha um cargo: ");
                        cargo = read.nextByte();
                    }

                    while ((cargo == 1 && qtdAQV == 1) || (cargo == 2 && qtdCoord == 2)) {
                        if (cargo == 1) {
                            System.out.println("\nAQV já definido(a). Você deve alterar o cargo ou excluir o(a) AQV para poder atribuir o cargo a outro usuário.");
                        } else {
                            System.out.println("\nQuantidade máxima de coordenadores atingida. Você deve alterar o cargo de um dos coordenadores ou fazer a exclusão de algum deles.");
                        }
                        System.out.print("Escolha um cargo: ");
                        cargo = read.nextByte();
                    }

                    cargoIgual = cargos[cargo].trim().equalsIgnoreCase(cadastro[id][5].trim());
                    System.out.println();
                }
                read.nextLine();
                cadastro[id][5] = cargos[cargo];
                System.out.println("Alteração feita com sucesso!");
            }
            formatarCabecalho(cadastro);

            buscarPorID(id, cadastro);

            System.out.print("\nDeseja continuar alterando o mesmo usuário? (s/n): ");
            continuarAlterando = Character.toLowerCase(read.next().

                    charAt(0));
            while (continuarAlterando != 's' && continuarAlterando != 'n') {
                System.out.println("\nOpção inválida. Tente novamente.");
                System.out.print("Deseja continuar alterando o mesmo usuário? (s/n): ");
                continuarAlterando = Character.toLowerCase(read.next().charAt(0));
            }
            System.out.println();
        } while (continuarAlterando == 's');
    }

    private static void carregarRegistrosDeAcesso() {
        if (!pastaRegistrosDeAcesso.exists()) {
            pastaRegistrosDeAcesso.mkdir();
            if (!arquivoRegistrosDeAcesso.exists()) {
                System.out.println("Nenhum registro de acesso encontrado.");
                return;
            }
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(arquivoRegistrosDeAcesso))) {
            StringBuilder recebeRegistros = new StringBuilder();
            String linha;
            while ((linha = reader.readLine()) != null) {
                recebeRegistros.append(linha).append("\n");
            }

            String[] infosUsuario = recebeRegistros.toString().split("\n");
            registrosDeAcesso = new String[infosUsuario.length][cabecalhoRegistrosDeAcesso.length];
            for (int usuario = 1; usuario < registrosDeAcesso.length; usuario++) {
                registrosDeAcesso[usuario] = infosUsuario[usuario].trim().split("\\|");
            }
            if (registrosDeAcesso.length > 1) {
                System.out.println("Registros de acesso carregados com sucesso!");
            } else {
                System.out.println("Nenhum registro de acesso encontrado.");
            }

        } catch (Exception ignored) {
        }
        registrosDeAcesso[0] = cabecalhoRegistrosDeAcesso;
    }

    private static void apagarRegistrosDeAcesso() {
        registrosDeAcesso = new String[][]{{null}};
        registrosDeAcesso[0] = cabecalhoRegistrosDeAcesso;
        salvarRegistrosDeAcesso();
    }

    private static void salvarRegistrosDeAcesso() {
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(arquivoRegistrosDeAcesso))) {
            StringBuilder recebeRegistros = new StringBuilder();
            for (int usuario = 0; usuario < registrosDeAcesso.length; usuario++) {
                for (byte secaoAcesso = 0; secaoAcesso < registrosDeAcesso[0].length; secaoAcesso++) {
                    recebeRegistros.append(formatarCadastro(registrosDeAcesso[usuario][secaoAcesso], secaoAcesso));
                }
                recebeRegistros.append("\n");
            }
            bufferedWriter.write(recebeRegistros.toString());
        } catch (Exception ignored) {
        }
    }

    private static void carregarMatriculas(){
        if(!pastaMatriculas.exists()){
            pastaMatriculas.mkdir();
            if(!arquivoMatriculas.exists()){
                System.out.println("Nenhuma matrícula encontrada.");
            }
            return;
        }

        try(BufferedReader buffReader = new BufferedReader(new FileReader(arquivoMatriculas))){
            StringBuilder recebeMatriculas = new StringBuilder();
            String lerMatricula;
            while((lerMatricula = buffReader.readLine()) != null){
                recebeMatriculas.append(lerMatricula).append("\n");
            }

            matriculas = recebeMatriculas.toString().split("\n");
        } catch (Exception ignored) {
        }
    }

    private static void salvarMatriculas(){
        try(BufferedWriter buffWriter = new BufferedWriter(new FileWriter(arquivoMatriculas))){
            StringBuilder recebeMatriculas = new StringBuilder();
            for (int linhaMatricula = 0; linhaMatricula < matriculas.length; linhaMatricula++) {
                if(!matriculas[linhaMatricula].isBlank()) {
                    recebeMatriculas.append(matriculas[linhaMatricula]).append("\n");
                }
            }
            buffWriter.write(recebeMatriculas.toString());
        } catch (Exception ignored){}
    }

}