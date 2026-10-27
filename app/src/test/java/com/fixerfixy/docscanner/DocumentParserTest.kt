package com.fixerfixy.docscanner

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentParserTest {

    @Test
    fun test1_LitlanInvoice() {
        val ocr = """
            Постачальник ТОВ "ЛІТЛАН" ЄДРПОУ 44508081, тел.
            P/p UA493348510000000026009350614 в АТ "ПУМБ" МФО 334851
            ІПН 445080804627, номер свідоцтва
            Адреса 49000, м. Дніпро, вул. Святослава Хороброго, б. 44
            Одержувач ТОВ "СОЮЗ ТЕЛЕКОМ"
            Замовлення Договір № ЛТ-24 від 29.01.24
            1. Орендна плата листопад 2026 року 1540.00
            Рахунок-фактура № ЛТ-0000176
            від 12 Жовтня 2026 р.
            Рахунок дійсний до сплати до 25.10.26
        """.trimIndent()
        assertEquals(
            "Рахунок-фактура_No_ЛТ-0000176_від_12.10.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test2_LitlanAct() {
        val ocr = """
            ЗАТВЕРДЖУЮ Директор ТОВ "ЛІТЛАН"
            І.к. 44508081 УКРАЇНА, МІСТО ДНІПРО
            АКТ № ЛТ-0000272
            Ідентифікаційний код 42880795
            здачі-прийняття робіт (надання послуг)
            склали цей акт про те, що Виконавцем були проведені такі роботи по договору № ЛТ-24 від 29.01.24:
            - Орендна плата вересень 2026 року
            Від Виконавця: 30-Вересня 2026 р.
            Від Замовника: 30 Вересня 2026 р.
        """.trimIndent()
        assertEquals(
            "Акт здачі-прийняття робіт_No_ЛТ-0000272_від_30.09.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test3_SanterraContract() {
        val ocr = """
            м. Дніпро
            ДОГОВІР № 24-08/26
            поставки та установки сонячної електростанції
            «24» серпня 2026 року
            1.1. Постачальник зобов'язується поставити обладнання, визначене у Специфікації (Додаток №1 до Договору).
            2.4. Остаточна вартість Робіт визначається на підставі підписаних Сторонами Актів здачі-приймання виконаних Робіт.
        """.trimIndent()
        assertEquals(
            "Договір_No_24-08-26_від_24.08.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test4_SanterraSpecification() {
        val ocr = """
            1.1 Сонячна панель Jinko Solar 620 Вт
            м. Дніпро
            Додаток № 1
            до Договору поставки № 24-08/26
            від «24» серпня 2026 року
            «24» серпня 2026 року
            Специфікація №1
            уклали цю Специфікацію на закупівлю товару:
        """.trimIndent()
        assertEquals(
            "Специфікація_№1_від_24.08.2026_до_Договору_№24-08-26_від_24.08.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test5_UkrtelecomRahunokAkt() {
        val ocr = """
            Рахунок-акт № 1240000000989370.8.2026. за електронні комунікаційні послуги за серпень 2026 р.
            згідно з договором № 125000-2225/21
            Дата: 31.08.2026
            IBAN UA943348510000000002600231843, в АТ "ПУМБ", м. Київ
            Особовий рахунок 1240000000989370
            Вид цивільно-правового договору: Надання послуг від 20.08.2021 № 125000-2225/21
            Сплатити до 20 вересня 2026 р.
            Залишок авансу на 01.08.2026 р., грн.
            Цей рахунок є одночасно актом передавання-приймання виконаних робіт за серпень 2026р.
            М.П. Укртелеком Ідентифікаційний код 21560766 №8
        """.trimIndent()
        assertEquals(
            "Рахунок-акт_No_1240000000989370.8.2026_від_31.08.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test6_UlyanychNakladna() {
        val ocr = """
            від 13.10.2026 р.
            ФОП Улянич Костянтин Федорович
            20300, Умань, вул. Єднання 4є, корпус 10, кв. 8
            НАКЛАДНА №
            39743
            Телефон: (067) 179-06-61
            р/р (IBAN) UA393510050000026008639633300
            Рахунок отримувача 26008639633300 АТ "УкрСиббанк" в м. Умань
            МФО 351005 Код ЄДРПОУ 2964412798
        """.trimIndent()
        assertEquals(
            "Накладна_No_39743_від_13.10.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test7_OrbitaActServices() {
        val ocr = """
            ЗАТВЕРДЖУЮ Генеральний директор 376
            Приватне акціонерне товариство "Науково-виробниче підприємство "Орбіта"
            72321 Бойко В.О. документів 833760463
            АКТ надання послуг
            № 1349 від 31 серпня 2026 р.
            склали цей акт про те, що на підставі наведених документів:
            Договір: 259/202-21 від 01.12.2021
            Виконавцем були виконані наступні роботи (надані такі послуги):
            Від Виконавця 31.08.2026
            п/р UA453054820000026005300774877
            49600, Дніпропетровська, Дніпро, Панікахи, будинок № 2
            Від Замовника 31.08.2026
        """.trimIndent()
        assertEquals(
            "Акт надання послуг_No_1349_від_31.08.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test8_SingleDigitActNumberAllowed() {
        val ocr = """
            АКТ надання послуг № 5 від 15 вересня 2026 р.
            по договору № 12/26 від 01.01.2026
        """.trimIndent()
        assertEquals(
            "Акт надання послуг_No_5_від_15.09.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }


    // ---------- Банківські відомості та зарплатні документи (знеособлені приклади) ----------

    private fun payoutStatement(dateLine: String, numberLine: String, half: String): String = """
        ПриватБанк
        Вул.Грушевського, 1Д, м. Київ, 01001, Україна
        тел.: 3700 E-mail: help@privatbank.ua
        Відомість розподілу виплат
        ТОВ ТЕСТ
        ЄДРПОУ 12345678
        від $dateLine
        # $numberLine
        № п/п Прізвище Ім'я По батькові Табельний ІПН Картка Сума Призначення
        1 Тестов Тест Тестович 1111111111 1111111111 4149 **** 0000 1 000.00 Заробітна плата-Заробітна плата за $half грудня 2025р
        2 Зразков Зразок Зразкович 2222222222 2222222222 5168 **** 1111 2 000.00 Заробітна плата-Заробітна плата за $half грудня 2025р
    """.trimIndent()

    @Test
    fun test9_BankPayoutStatementSlashDate() {
        val ocr = payoutStatement("07/01/2026, 14:45", "260107PB000052979023", "2-гу пол.")
        assertEquals(
            "Відомість розподілу виплат_No_260107PB000052979023_від_07.01.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test10_BankPayoutStatementFirstHalf() {
        val ocr = payoutStatement("22/12/2025, 11:58", "251222PB000052652331", "1-шу пол.")
        assertEquals(
            "Відомість розподілу виплат_No_251222PB000052652331_від_22.12.2025_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test11_BankPayoutStatementOcrNoise() {
        // кириличні Р і В замість PB, латинські O замість нулів
        val ocr = payoutStatement("22/12/2025, 11:58", "251222РВ00OO52652331", "1-шу пол.")
        assertEquals(
            "Відомість розподілу виплат_No_251222PB000052652331_від_22.12.2025_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test12_BankPayoutStatementSpaceInsideNumber() {
        val ocr = payoutStatement("22/12/2025, 11:58", "251222PB 000052652331", "1-шу пол.")
        assertEquals(
            "Відомість розподілу виплат_No_251222PB000052652331_від_22.12.2025_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test13_BankPayoutStatementDateFromNumber() {
        // дату в шапці не розпізнано - беремо з номера (ррммдд)
        val ocr = """
            Відомість розподілу виплат
            ТОВ ТЕСТ
            # 260107PB000052979023
            Заробітна плата за 2-гу пол. грудня 2025р
        """.trimIndent()
        assertEquals(
            "Відомість розподілу виплат_No_260107PB000052979023_від_07.01.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test14_HalfMonthPhraseIsNotADate() {
        val ocr = "Заробітна плата-Заробітна плата за 2-гу пол. грудня 2025р"
        assertEquals("не_вказано", DocumentParser.parse(ocr).date)
    }

    @Test
    fun test15_DetailedPayrollCalculation() {
        val ocr = """
            Детальний розрахунок нарахувань
            до документа
            №29 від 20 грудня 2025 р.
            Організація: ТОВ "ТЕСТ"
            Працівник: Тестов Тест Тестович, таб. №: 19
            1. Розрахунок середнього заробітку
            Грудень 2024 34 795,45 120 000,00
            2. Лікарняний лист
            Лікарняний 20.12.2025 20.12.2025 1 1 070,31
        """.trimIndent()
        assertEquals(
            "Детальний розрахунок нарахувань_No_29_від_20.12.2025_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test16_SalaryListToBank() {
        val ocr = """
            СПИСОК ЗАРПЛАТИ, ЩО ПЕРЕРАХОВУЄТЬСЯ В БАНК
            № 91 від 17.12.2025 р.
            Організація: ТОВ "ТЕСТ" Аванс за грудень '25р
            Одиниця виміру: грн
            № з/п Особовий рахунок Прізвище, ім'я, по батькові Сума
            1 1111111111 Тестов Тест Тестович, 1111111111 3 132,06
        """.trimIndent()
        assertEquals(
            "Список зарплати в банк_No_91_від_17.12.2025_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }

    @Test
    fun test17_PayoutTitleWithOcrErrors() {
        // "і" розпізнано як "1" у заголовку
        val ocr = payoutStatement("07/01/2026, 14:45", "260107PB000052979023", "2-гу пол.")
            .replace("Відомість розподілу виплат", "Від0мість розпод1лу виплат")
        assertEquals(
            "Відомість розподілу виплат_No_260107PB000052979023_від_07.01.2026_р.pdf",
            DocumentParser.parse(ocr).buildStandardFileName("pdf")
        )
    }
}
