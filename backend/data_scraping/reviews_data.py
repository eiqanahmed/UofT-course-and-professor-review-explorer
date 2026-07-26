import asyncio
from playwright.async_api import async_playwright
from bs4 import BeautifulSoup
import pandas as pd


def scrape_review_card(review_li, prof_id):
    try:
        def safe_text(selector):
            return selector.get_text(" ", strip=True) if selector else None

        # Course
        course_code = safe_text(review_li.select_one('.RatingHeader__StyledClass-sc-1dlkqw1-3'))

        # Date
        date = safe_text(review_li.select_one('.TimeStamp__StyledTimeStamp-sc-9q2r30-0'))

        # Ratings
        quality = difficulty = None
        ratings = review_li.select('.CardNumRating__StyledCardNumRating-sc-17t4b9u-0')
        for rating in ratings:
            label = safe_text(rating.select_one('.CardNumRating__CardNumRatingHeader-sc-17t4b9u-1'))
            score = safe_text(rating.select_one('.CardNumRating__CardNumRatingNumber-sc-17t4b9u-2'))
            if label == "Quality":
                quality = float(score)
            elif label == "Difficulty":
                difficulty = float(score)

        # Meta info
        meta_items = review_li.select('.MetaItem__StyledMetaItem-y0ixml-0')
        meta_info = {}
        for item in meta_items:
            try:
                label = item.contents[0].strip().lower().replace(' ', '_').replace(":", "")
                value_span = item.select_one('span')
                value = value_span.get_text(strip=True) if value_span else None
                if label and value:
                    meta_info[label] = value
            except Exception as e:
                continue

        # Comment
        comment_tag = review_li.select_one('.Comments__StyledComments-dzzyvm-0')
        comment = safe_text(comment_tag)

        # Thumbs
        thumbs_up_tag = review_li.select_one('#thumbs_up .Thumbs__HelpTotalNumber-sc-19shlav-2')
        thumbs_down_tag = review_li.select_one('#thumbs_down .Thumbs__HelpTotalNumber-sc-19shlav-2')
        thumbs_up = int(thumbs_up_tag.get_text(strip=True)) if thumbs_up_tag else 0
        thumbs_down = int(thumbs_down_tag.get_text(strip=True)) if thumbs_down_tag else 0

        # Skip empty reviews
        if not course_code and not comment:
            return None

        return {
            "prof_id": prof_id,
            "course_code": course_code,
            "date": date,
            "quality": quality,
            "difficulty": difficulty,
            "for_credit": meta_info.get("for_credit"),
            "attendance": meta_info.get("attendance"),
            "would_take_again": meta_info.get("would_take_again"),
            "grade": meta_info.get("grade"),
            "textbook": meta_info.get("textbook"),
            "comment": comment,
            "thumbs_up": thumbs_up,
            "thumbs_down": thumbs_down,
        }

    except Exception as e:
        print(f"Error parsing review card for prof {prof_id}: {e}")
        return None


async def scrape_all_reviews(prof_url, prof_id, expected_num_reviews):
    async with async_playwright() as p:
        browser = await p.firefox.launch(headless=True)
        page = await browser.new_page()

        # Retry logic for loading the page
        success = False
        for attempt in range(3):
            try:
                await page.goto(prof_url, timeout=90000, wait_until="domcontentloaded")
                await page.wait_for_timeout(1500)
                await page.evaluate("window.scrollBy(0, 1500)")
                success = True
                break
            except Exception as e:
                print(f"Retry {attempt + 1}/3 failed: {e}")
                await page.wait_for_timeout(3000)

        if not success:
            raise TimeoutError(f"Failed to load {prof_url} after 3 retries.")

        # Scroll and click "Load More Ratings" until we've reached the expected number

        seen_reviews = 0
        while True:
            await page.mouse.wheel(0, 2000)
            await page.wait_for_timeout(1000)

            html = await page.content()
            soup = BeautifulSoup(html, 'html.parser')
            review_cards = soup.select('#ratingsList > li')
            seen_reviews = len(review_cards)

            if seen_reviews >= expected_num_reviews:
                break

            show_more = page.locator('button:has-text("Load More Ratings")')
            if await show_more.count() > 0:
                try:
                    await show_more.scroll_into_view_if_needed()
                    await show_more.click()
                    await page.wait_for_timeout(1500)
                except:
                    break
            else:
                break

        html = await page.content()
        soup = BeautifulSoup(html, 'html.parser')
        review_cards = soup.select('#ratingsList > li')
        # parsed_reviews = [scrape_review_card(card, prof_id) for card in review_cards]
        all_reviews = []
        for card in review_cards:
            parsed = scrape_review_card(card, prof_id)
            if parsed:  # Filter out None or failed cards
                all_reviews.append(parsed)
        # return pd.DataFrame(parsed_reviews)
        df = pd.DataFrame(all_reviews)
        return df


async def scrape_reviews_for_all_profs(prof_csv_path):
    df = pd.read_csv(prof_csv_path)
    all_reviews = []

    for _, row in df.iterrows():
        error = row.get("error", "")
        if not isinstance(error, str) or error.strip() == "":
            url = row['rmp_profile_url']
            prof_id = row['rmp_professor_id']
            num_reviews = int(row["num_reviews"])
            print(f"🔍 Scraping reviews for {row['name']} ({prof_id})")
            try:
                reviews = await scrape_all_reviews(url, prof_id, num_reviews)
                all_reviews.append(reviews)
            except Exception as e:
                print(f"Failed for {prof_id}: {e}")

    reviews_df = pd.concat(all_reviews, ignore_index=True)
    reviews_df.to_csv("all_reviews.csv", index=False)
    print("Saved reviews to all_reviews.csv")


# if __name__ == "__main__":
#     asyncio.run(scrape_reviews_for_all_profs("professor_data.csv"))
if __name__ == "__main__":
    # test_prof_url = "https://www.ratemyprofessors.com/professor/3041628"  # Example: Salvador Alanis
    # test_prof_id = "3041628"
    # test_num_r = 4
    #
    # async def main():
    #     reviews = await scrape_all_reviews(test_prof_url, test_prof_id, test_num_r)
    #     reviews.to_csv("test_reviews.csv", index=False)
    #     print(f"Saved {len(reviews)} reviews for professor {test_prof_id} to test_reviews.csv")
    #
    #
    # asyncio.run(main())

    # df = pd.read_csv("professor_data.csv")
    # df.head(2).to_csv("test_professor_data.csv", index=False)
    asyncio.run(scrape_reviews_for_all_profs("prof_data.csv"))


