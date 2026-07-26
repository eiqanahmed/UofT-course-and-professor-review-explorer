import asyncio
from playwright.async_api import async_playwright
from bs4 import BeautifulSoup
import pandas as pd


def scrape_professor_card(prof_a_tag):
    try:
        name = prof_a_tag.select_one('.CardName__StyledCardName-sc-1gyrgim-0').get_text(strip=True)
        department = prof_a_tag.select_one('.CardSchool__Department-sc-19lmz2k-0').get_text(strip=True)
        university = prof_a_tag.select_one('.CardSchool__School-sc-19lmz2k-1').get_text(strip=True)
        avg_rating = float(prof_a_tag.select_one('.CardNumRating__CardNumRatingNumber-sc-17t4b9u-2').get_text(strip=True))
        num_reviews_raw = prof_a_tag.select_one('.CardNumRating__CardNumRatingCount-sc-17t4b9u-3').get_text(strip=True)
        num_reviews = int(num_reviews_raw.split()[0])
        feedback_numbers = prof_a_tag.select('.CardFeedback__CardFeedbackNumber-lq6nix-2')
        take_again_pct = int(feedback_numbers[0].get_text(strip=True).replace('%', '')) if feedback_numbers else None
        difficulty = float(feedback_numbers[1].get_text(strip=True)) if len(feedback_numbers) > 1 else None
        relative_url = prof_a_tag['href']
        full_url = f"https://www.ratemyprofessors.com{relative_url}"
        prof_id = relative_url.split('/')[-1]

        return {
            "name": name,
            "department": department,
            "university": university,
            "avg_rating": avg_rating,
            "num_reviews": num_reviews,
            "take_again_pct": take_again_pct,
            "difficulty": difficulty,
            "rmp_profile_url": full_url,
            "rmp_professor_id": prof_id
        }
    except Exception as e:
        return {"error": str(e)}


async def scrape_rmp_professors():
    url = "https://www.ratemyprofessors.com/search/professors/12184?q=*"
    async with async_playwright() as p:
        browser = await p.firefox.launch(headless=True)
        page = await browser.new_page()
        await page.goto(url, timeout=60000, wait_until="domcontentloaded")

        df = pd.DataFrame(columns=[
            "name", "department", "university", "avg_rating", "num_reviews",
            "take_again_pct", "difficulty", "rmp_profile_url", "rmp_professor_id"
        ])
        seen_ids = set()

        while True:
            try:
                # Step 1: Scroll
                await page.evaluate("window.scrollBy(0, 2000)")
                await page.wait_for_timeout(1500)

                # Step 2: Click "Show More"
                show_more_button = page.locator('button:has-text("Show More")')
                if await show_more_button.count() > 0:
                    print("Clicking 'Show More'...")
                    await show_more_button.scroll_into_view_if_needed()
                    await page.wait_for_timeout(500)
                    await show_more_button.click()
                    await page.wait_for_timeout(1500)

                # Step 3: Extra scrolling
                for _ in range(3):
                    await page.evaluate("window.scrollBy(0, 2000)")
                    await page.wait_for_timeout(1000)

                # Step 4: Parse cards
                html = await page.content()
                soup = BeautifulSoup(html, 'html.parser')
                cards = soup.select('a.TeacherCard__StyledTeacherCard-syjs0d-0')

                new_count = 0
                for card in cards:
                    url = card.get("href", "")
                    prof_id = url.split("/")[-1]
                    if prof_id in seen_ids:
                        continue
                    seen_ids.add(prof_id)
                    prof = scrape_professor_card(card)
                    df = pd.concat([df, pd.DataFrame([prof])], ignore_index=True)
                    new_count += 1

                print(f"Added {new_count} new professors (total: {len(df)})")

                if new_count == 0 and await show_more_button.count() == 0:
                    print("Done scraping.")
                    break

            except Exception as e:
                print(f"Error: {e}")
                break

        df.to_csv("professor_data.csv")
        print("Saved to professor_data.csv")

# Run it
if __name__ == "__main__":
    asyncio.run(scrape_rmp_professors())
